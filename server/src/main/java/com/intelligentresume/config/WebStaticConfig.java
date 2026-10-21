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
}
