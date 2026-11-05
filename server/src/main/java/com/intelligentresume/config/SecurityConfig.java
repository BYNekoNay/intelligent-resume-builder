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
 *
 * <p>修改前必须确认的四件事:
 * <ul>
 *   <li><b>新增公开端点要显式加进 permitAll</b>;漏加会被 401 拦在控制器之前,且不会有业务日志。</li>
 *   <li><b>会话是无状态的</b>:不创建 HttpSession,refresh 走 Cookie。CSRF 因此关闭,
 *       依赖 Cookie 的 {@code SameSite=Lax} 与「写操作不靠 Cookie 鉴权」这两个前提。</li>
 *   <li><b>过滤器顺序有语义</b>:限流与 JWT 都在 {@code UsernamePasswordAuthenticationFilter} 之前,
 *       SPA 回落挂在 JWT 之后;调换会让 429 与鉴权的先后、以及 SPA 深路由的行为改变。</li>
 *   <li><b>401 的响应体由 authenticationEntryPoint 统一生成</b>(JSON 信封 + traceId),
 *       不要退回框架默认的 HTML/空响应,前端按信封解包。</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper objectMapper;
    private final DesktopModeProperties desktopModeProperties;
    private final SpaFallbackFilter spaFallbackFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          ObjectMapper objectMapper,
                          DesktopModeProperties desktopModeProperties,
                          SpaFallbackFilter spaFallbackFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.objectMapper = objectMapper;
        this.desktopModeProperties = desktopModeProperties;
        this.spaFallbackFilter = spaFallbackFilter;
    }

    /**
     * 安全过滤链。
     *
     * <p>顺序副作用：限流先于 JWT 注册，意味着超限请求在解析令牌之前就被 429；
     * 桌面模式下 {@code anyRequest().permitAll()} 只放行非 /api 的 SPA 资源，
     * {@code /api/**} 仍需认证（由 JwtAuthenticationFilter 注入的本地用户满足）。
     */
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
                        // CSP 违规报告（B6-1）：由浏览器自动发出、不带凭证，必须匿名可达；
                        // 恒返回 204，解析与计数细节见 CspReportController
                        "/api/system/csp-reports",
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
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // SPA 回落挂在 security chain 内（而不是依赖 servlet 容器的隐式 filter 注册）：
            // 隐式注册并不保证它在 DispatcherServlet 之前生效，实测深路由会直接 404。
            .addFilterAfter(spaFallbackFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
