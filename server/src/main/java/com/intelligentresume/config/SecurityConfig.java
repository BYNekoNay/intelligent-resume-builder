package com.intelligentresume.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.auth.jwt.JwtAuthenticationFilter;
import com.intelligentresume.auth.ratelimit.RateLimitFilter;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 总配置。
 *
 * <p>骨架:T02 落地认证过滤器、限流、白名单。
 *
 * <p>白名单采用精确端点而非 {@code /api/auth/**} 通配，确保 {@code /me}、
 * {@code /logout-all} 等需要登录的端点由安全框架统一返回 401，
 * 而不是落到应用层 {@code currentUserId()} 抛异常兜底。
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper objectMapper;
    private final DesktopModeProperties desktopModeProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          ObjectMapper objectMapper,
                          DesktopModeProperties desktopModeProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.objectMapper = objectMapper;
        this.desktopModeProperties = desktopModeProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                        "/api/auth/register",
                        "/api/auth/login",
                        // 删除撤销（D2 阶段 3）：删号后会话已全撤，恢复入口必须匿名可达（凭密码验证）
                        "/api/auth/deletion/restore",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/api/system/health",
                        // /actuator/* 在公网经 nginx 不可达（nginx 无 /actuator/ location，请求落入 SPA 回退返回 index.html）；
                        // permitAll 仅供本地 monitoring/ 栈经容器网络直连 api:8080 使用。
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus"
                ).permitAll();
                if (desktopModeProperties.isEnabled()) {
                    // 桌面本地工作区（ADR-012 D1/D3）：**API 仍然受认证保护**（过滤器注入本地用户，
                    // 越权面不变），但前端 SPA 壳必须匿名可达 —— 否则刷新任何前端路由都会被 401 拦下，
                    // 而 index.html 与构建产物本身不含用户数据。
                    auth.requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll();
                } else {
                    auth.anyRequest().authenticated();
                }
            })
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
                ApiResponse<Void> body = ApiResponse.failure(
                        ErrorCode.UNAUTHENTICATED.code(),
                        ErrorCode.UNAUTHENTICATED.message(),
                        traceId == null ? null : traceId.toString());
                response.getWriter().write(objectMapper.writeValueAsString(body));
            }))
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
