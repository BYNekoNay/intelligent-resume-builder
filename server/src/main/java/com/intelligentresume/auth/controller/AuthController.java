package com.intelligentresume.auth.controller;

import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.config.DesktopLocalUserService;
import com.intelligentresume.auth.dto.ChangeEmailRequest;
import com.intelligentresume.auth.dto.ChangePasswordRequest;
import com.intelligentresume.auth.dto.ExportDataRequest;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.dto.UpdateProfileRequest;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.auth.service.AccountExportService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.ClientIpResolver;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * 认证 HTTP 入口：注册、登录、刷新、登出、当前用户、凭证变更、删号与数据导出。
 *
 * <p>【小白导览】①解决什么业务问题：前端与认证服务之间唯一的 HTTP 窗口——前端打的
 * 所有 {@code /api/auth/**} 请求都进这里，它做三件事：参数校验、调用 {@link AuthService}、
 * 以及管理 refresh token 的 Cookie（一种浏览器自动保存/回传的小数据，这里存刷新令牌）。
 * ②在链路哪里：上游是前端，下游是 AuthService（业务）与 Cookie（令牌投递）；
 * 令牌本身由 TokenService 造、AuthService 编排，控制器只管投递与回收。
 * ③改坏它用户会看到什么：Cookie 属性写错，登录成功但下一步刷新永远失败（浏览器不回传 Cookie）；
 * 忘了在改密后清 Cookie，旧设备看起来还「在线」；导出的编码错了，中文全是问号。
 *
 * <p>要点（改动前先读）：
 * <ul>
 *   <li>access token 放响应体，refresh token 只经 HttpOnly Cookie 下发；请求侧优先读
 *       {@code X-Refresh-Token} 头（便于调试），其次读 Cookie。</li>
 *   <li>改密/改邮箱/删号成功后既要服务端撤销会话（AuthService），也要在这里下发过期
 *       Cookie——只做一边会让客户端继续持有失效令牌。</li>
 *   <li>{@code currentUserId} 属性由 {@link com.intelligentresume.auth.jwt.JwtAuthenticationFilter}
 *       写入；取不到即 401，控制器不自行解析令牌。</li>
 *   <li>桌面本地模式没有 refresh cookie：{@code /refresh} 直接签发本地用户令牌，
 *       {@code DELETE /me} 显式返回 409（详见方法内注释）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_HEADER = "X-Refresh-Token";

    private final AuthService authService;
    private final AccountExportService accountExportService;
    private final ClientIpResolver clientIpResolver;
    private final DesktopLocalUserService desktopLocalUserService;
    private final String refreshCookieName;
    private final long refreshCookieMaxAge;
    private final boolean refreshCookieSecure;

    public AuthController(AuthService authService,
                          AccountExportService accountExportService,
                          ClientIpResolver clientIpResolver,
                          DesktopLocalUserService desktopLocalUserService,
                          @Value("${app.jwt.refresh-cookie.name}") String refreshCookieName,
                          @Value("${app.jwt.refresh-cookie.max-age}") long refreshCookieMaxAge,
                          @Value("${app.jwt.refresh-cookie.secure}") boolean refreshCookieSecure) {
        this.authService = authService;
        this.accountExportService = accountExportService;
        this.clientIpResolver = clientIpResolver;
        this.desktopLocalUserService = desktopLocalUserService;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookieMaxAge = refreshCookieMaxAge;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return withRefreshCookie(authService.register(request), httpRequest, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        TokenResponse tokens = authService.login(request);
        return withRefreshCookie(tokens, httpRequest);
    }

    /**
     * 删除撤销（决策 D2 阶段 3）：撤销窗口内的账号凭用户名/邮箱 + 密码恢复并直接登录。
     * 匿名可达；限流同登录（凭据验证面，见 RateLimitFilter）。
     */
    @PostMapping("/deletion/restore")
    public ResponseEntity<ApiResponse<TokenResponse>> restoreDeletion(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        TokenResponse tokens = authService.restoreDeletion(request);
        return withRefreshCookie(tokens, httpRequest);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(HttpServletRequest httpRequest) {
        // 桌面本地工作区（ADR-012 D3）：无需 refresh cookie，直接为本地用户签发令牌。
        // 前端沿用在线版启动流程（先 refresh 再拉当前用户），这条分支就是「免登录」的协议落点。
        if (desktopLocalUserService.isEnabled()) {
            return ResponseEntity.ok()
                    .body(ApiResponse.success(desktopLocalUserService.issueToken(), traceId(httpRequest)));
        }
        String refresh = extractRefreshToken(httpRequest);
        String ua = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ip = clientIpResolver.resolve(httpRequest);
        return withRefreshCookie(authService.refresh(refresh, ua, ip), httpRequest);
    }

    /** 登出：只撤销当前 refresh 会话；令牌缺失或已撤销也返回成功（幂等，前端无需区分）。 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        authService.logout(extractRefreshToken(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    /** 撤销该用户全部未失效会话（改密/改邮箱内部也走同一条路径），随后清掉浏览器 Cookie。 */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        authService.logoutAll(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.currentUser(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/me")
    public ApiResponse<CurrentUserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.updateProfile(currentUserId(httpRequest), request), traceId(httpRequest));
    }

    /** 改邮箱：校验当前密码，成功后撤销全部会话并清 Cookie（新邮箱 + 旧口令不再能免登录）。 */
    @PostMapping("/me/email")
    public ResponseEntity<ApiResponse<Void>> changeEmail(@Valid @RequestBody ChangeEmailRequest request, HttpServletRequest httpRequest) {
        authService.changeEmail(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    /** 改密码：校验当前密码，成功后撤销全部会话并清 Cookie——所有设备都必须重新登录。 */
    @PostMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        authService.changePassword(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(HttpServletRequest httpRequest) {
        // 桌面本地工作区（ADR-012 D3）：删号必须被拒绝。
        // 在线版的删号是「7 天撤销窗口 → 级联硬删该用户全部数据」，在桌面语境下等于
        // 「点一下删号 → 整个本地工作区被物理清空」，且不可撤销。
        // 清空数据的正确路径是备份/恢复（恢复前会自动留安全备份，见 ADR-012 D6/R39）。
        if (desktopLocalUserService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                    .body(ApiResponse.failure(ErrorCode.CONFLICT.code(),
                            "桌面本地工作区不支持删除账号：如需清空数据，请使用「恢复备份」"
                                    + "（恢复前会自动创建当前工作区的安全备份）",
                            traceId(httpRequest)));
        }
        authService.deleteAccount(currentUserId(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    /**
     * 导出当前用户在各业务域的个人数据（ideation #7）。
     *
     * <p>返回缩进 JSON 文件（{@code Content-Disposition: attachment}），不是统一
     * {@code ApiResponse} 信封——导出物是可直接保存/迁移的数据文档。
     *
     * <p>**流式写出**：账号数据无体积上限，整份缓冲的峰值堆 ≈ 2× 响应体（先建 JSON 字符串、
     * 再编码成字节数组），实测 12.2MB 响应在 {@code -Xmx128m} 上直接 OutOfMemoryError；
     * 流式写出的堆占用为常数级，且事务在序列化前已提交（DB 连接不在 JSON 生成期间被占用）。
     * 代价：响应无 {@code Content-Length}（分块传输），且流已写出后无法再改写为错误信封。
     */
    @PostMapping("/export")
    public void exportData(@Valid @RequestBody ExportDataRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        authService.verifyCurrentPassword(currentUserId(httpRequest), request.currentPassword());
        Map<String, Object> payload = accountExportService.loadExportPayload(currentUserId(httpRequest));
        String fileName = "intelligent-resume-export-" + LocalDate.now() + ".json";
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        // 显式声明 charset：默认 ISO-8859-1 会把中文数据写成 '?'
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
        accountExportService.writePayloadAsJson(payload, httpResponse.getOutputStream());
    }

    private String extractRefreshToken(HttpServletRequest request) {
        // 优先 header(便于前端调试),其次 cookie
        String header = request.getHeader(REFRESH_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (refreshCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 取当前用户 id：只认过滤器写入的 request 属性，不解析令牌。
     * 属性为空说明请求未通过认证链——宁可 401，也不能退化成匿名访问。
     */
    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    private ResponseEntity<ApiResponse<TokenResponse>> withRefreshCookie(TokenResponse tokens, HttpServletRequest request) {
        return withRefreshCookie(tokens, request, HttpStatus.OK);
    }

    /**
     * 签发令牌的统一出口：refresh token 只写 Set-Cookie，响应体里的 {@code TokenResponse.refreshToken}
     * 因 {@code @JsonIgnore} 不会出现在 JSON 中。新增签发入口必须走这里，否则 refresh token 会漏进响应体。
     */
    private ResponseEntity<ApiResponse<TokenResponse>> withRefreshCookie(TokenResponse tokens, HttpServletRequest request, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                .body(ApiResponse.success(tokens, traceId(request)));
    }

    /**
     * 构造 refresh Cookie：{@code path=/} 使所有 API 都能带上它，HttpOnly 防 XSS 读取，
     * SameSite=Lax 兼容跳转式导航；{@code secure} 由配置决定——本地 http 环境必须为 false，否则浏览器不会回传。
     */
    private ResponseCookie refreshCookie(String refreshToken) {
        return ResponseCookie.from(refreshCookieName, refreshToken)
                .path("/")
                .maxAge(refreshCookieMaxAge)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(refreshCookieName, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .build();
    }
}
