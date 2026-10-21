package com.intelligentresume.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SPA 路由回落：把非后端端点的 GET 请求转发到 {@code index.html}。
 *
 * <p>前端用 history 模式（22 个懒加载路由），刷新 {@code /resumes/123} 必须回到 index.html，
 * 否则桌面端（由本应用直接托管前端）会得到 404。
 *
 * <p>判定条件刻意保守，只回落「看起来像前端路由」的请求：
 * <ul>
 *   <li>路径不在 {@code /api}、{@code /actuator}、{@code /error} 之下
 *       —— 否则会把 JSON 接口变成 HTML，是典型的「看起来 200 其实坏了」；</li>
 *   <li>路径不含扩展名 —— {@code /assets/x.js}、{@code /favicon.ico} 是静态资源，不是路由；</li>
 *   <li>客户端接受 HTML（浏览器导航）—— API 客户端即使路径不含扩展名也不该被回落。</li>
 * </ul>
 *
 * <p>只在桌面本地工作区启用：在线版的前端由 nginx 提供（{@code try_files ... /index.html}），
 * 本过滤器对它没有意义。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SpaFallbackFilter extends OncePerRequestFilter {

    private static final String INDEX = "/index.html";

    private final DesktopModeProperties properties;

    public SpaFallbackFilter(DesktopModeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (properties.isEnabled() && "GET".equalsIgnoreCase(request.getMethod())
                && isFrontendRoute(request)) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isFrontendRoute(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        if (uri.startsWith("/api") || uri.startsWith("/actuator") || uri.startsWith("/error")) {
            return false;
        }
        // 带扩展名的是静态资源（/assets/x.js、/favicon.ico），不是前端路由
        if (uri.lastIndexOf('/') < uri.lastIndexOf('.')) {
            return false;
        }
        String accept = request.getHeader("Accept");
        // `*/*` 表示「接受任意类型」（curl 的默认 Accept 就是它），回落 HTML 对它是安全的；
        // 只有明确只要 JSON（如 API 客户端）才不该被回落。
        return accept == null || accept.contains("text/html") || accept.contains("*/*");
    }
}
