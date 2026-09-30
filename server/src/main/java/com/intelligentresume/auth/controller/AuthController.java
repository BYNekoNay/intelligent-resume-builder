package com.intelligentresume.auth.controller;

import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.auth.dto.ChangeEmailRequest;
import com.intelligentresume.auth.dto.ChangePasswordRequest;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_HEADER = "X-Refresh-Token";

    private final AuthService authService;
    private final AccountExportService accountExportService;
    private final ClientIpResolver clientIpResolver;
    private final String refreshCookieName;
    private final long refreshCookieMaxAge;
    private final boolean refreshCookieSecure;

    public AuthController(AuthService authService,
                          AccountExportService accountExportService,
                          ClientIpResolver clientIpResolver,
                          @Value("${app.jwt.refresh-cookie.name}") String refreshCookieName,
                          @Value("${app.jwt.refresh-cookie.max-age}") long refreshCookieMaxAge,
                          @Value("${app.jwt.refresh-cookie.secure}") boolean refreshCookieSecure) {
        this.authService = authService;
        this.accountExportService = accountExportService;
        this.clientIpResolver = clientIpResolver;
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

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(HttpServletRequest httpRequest) {
        String refresh = extractRefreshToken(httpRequest);
        String ua = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        String ip = clientIpResolver.resolve(httpRequest);
        return withRefreshCookie(authService.refresh(refresh, ua, ip), httpRequest);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        authService.logout(extractRefreshToken(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

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

    @PostMapping("/me/email")
    public ResponseEntity<ApiResponse<Void>> changeEmail(@Valid @RequestBody ChangeEmailRequest request, HttpServletRequest httpRequest) {
        authService.changeEmail(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @PostMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        authService.changePassword(currentUserId(httpRequest), request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.success(null, traceId(httpRequest)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(HttpServletRequest httpRequest) {
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
    @GetMapping("/export")
    public void exportData(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
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

    private ResponseEntity<ApiResponse<TokenResponse>> withRefreshCookie(TokenResponse tokens, HttpServletRequest request, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                .body(ApiResponse.success(tokens, traceId(request)));
    }

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
