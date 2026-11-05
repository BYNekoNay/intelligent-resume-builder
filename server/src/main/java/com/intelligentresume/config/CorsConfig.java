package com.intelligentresume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS 配置：仅对 {@code /api/**} 生效，来源列表来自 {@code app.cors.allowed-origins}（逗号分隔）。
 *
 * <p>要点：
 * <ul>
 *   <li>因为 {@code allowCredentials(true)}（refresh token 走 Cookie），来源必须是**显式清单**，
 *       不能用 {@code "*"}——浏览器与 Spring 都不允许两者共存。</li>
 *   <li>{@code allowedHeaders} 是白名单：新增自定义请求头（如幂等键）必须同步加这里，否则预检失败。</li>
 *   <li>{@code exposedHeaders} 只放行 {@code X-Trace-Id}；前端要读别的响应头需同步添加。</li>
 *   <li>若线上前面还有 nginx，CORS 只应由一层负责，重复写头会让浏览器直接拒绝响应。</li>
 * </ul>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 注册映射。来源字符串按逗号拆分并 trim/丢弃空项：配置里留尾逗号不会产生空 origin。
     * 允许的方法与头都显式列举，避免后续新增方法默认被拒。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", "X-Trace-Id", "X-Refresh-Token")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true);
    }
}
