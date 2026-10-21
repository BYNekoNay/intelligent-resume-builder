package com.intelligentresume.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * 桌面本地工作区的前端托管（ADR-012 D1）。
 *
 * <p>在线版的前端由 nginx 提供（{@code deploy/nginx/host.conf}），后端只提供 API。
 * 桌面端没有 nginx，因此由本应用直接托管前端产物：
 * <ul>
 *   <li>{@code /assets/**} 等构建产物走静态资源处理器；</li>
 *   <li>其余前端路由（history 模式，22 个懒加载路由）由 {@link SpaFallbackFilter} 回落到 {@code index.html}。</li>
 * </ul>
 *
 * <p>回落必须排除 {@code /api/**} 与 {@code /actuator/**}：它们是后端端点，
 * 回落到 index.html 会把 JSON 接口变成 HTML，是典型的「看起来 200 其实坏了」。
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/favicon.ico");
    }

    /**
     * SPA 路由回落：把非后端端点的 GET 请求转发到 index.html。
     *
     * <p>判定条件刻意保守：只回落「看起来像前端路由」的请求 —— 路径不含点（无文件扩展名）、
     * 不在 {@code /api} 与 {@code /actuator} 前缀下、且接受 HTML。
     */
    @Component
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class SpaFallbackFilter extends OncePerRequestFilter {

        private static final String INDEX = "/index.html";

        private final DesktopModeProperties properties;

        public SpaFallbackFilter(DesktopModeProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (!properties.isEnabled() || !"GET".equalsIgnoreCase(request.getMethod())
                    || !isFrontendRoute(request)) {
                chain.doFilter(request, response);
                return;
            }
            request.getRequestDispatcher(INDEX).forward(request, response);
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
            return accept == null || accept.contains("text/html");
        }
    }
}
