package com.intelligentresume.auth.jwt;

import com.intelligentresume.auth.service.ActiveUserCache;
import com.intelligentresume.config.DesktopLocalUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 {@code Authorization: Bearer <jwt>},写入 {@code request.currentUserId} 与 SecurityContext。
 * 解析失败时不抛出 401,只保留无认证状态——由后续链路在需要时抛 UNAUTHENTICATED。
 *
 * <p>用户状态校验（#6 安全闭环）走 {@link ActiveUserCache} 短 TTL 缓存（PA-2），
 * 避免每个请求都查 user 表；删号路径在事务提交后显式清除缓存，保持「删号即失效」。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性名：AuthController 等取当前用户的唯一入口，改名要全局同步。 */
    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final ActiveUserCache activeUserCache;
    private final DesktopLocalUserService desktopLocalUserService;

    public JwtAuthenticationFilter(TokenService tokenService,
                                   ActiveUserCache activeUserCache,
                                   DesktopLocalUserService desktopLocalUserService) {
        this.tokenService = tokenService;
        this.activeUserCache = activeUserCache;
        this.desktopLocalUserService = desktopLocalUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 桌面本地工作区（ADR-012 D3）：恒定本地用户直接注入，**不解析客户端令牌**。
        // 桌面端只有一个工作区，令牌指向别的账号没有意义，统一收敛到本地用户更安全。
        if (desktopLocalUserService.isEnabled()) {
            Long localUserId = desktopLocalUserService.localUserId();
            if (localUserId != null) {
                request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, localUserId);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        localUserId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
            return;
        }
        // 只认 "Bearer " 前缀。令牌无效、过期或用户非 ACTIVE 时**静默放行**（不在此处返回 401）：
        // 白名单端点（登录/注册/健康检查）也走这条过滤器，提前拒绝会误伤匿名请求；
        // 需要登录的端点由 SecurityConfig 的 authenticated() 统一兜 401。
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Long userId = tokenService.parseUserId(token);
            if (userId != null && activeUserCache.isActive(userId)) {
                request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, userId);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
