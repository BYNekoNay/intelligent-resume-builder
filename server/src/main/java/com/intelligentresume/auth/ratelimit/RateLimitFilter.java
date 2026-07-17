package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录/注册/刷新接口的内存令牌桶限流(MVP)。
 *
 * <p>按客户端 IP + path 组合分桶,达到阈值时返回 429。
 * 不引入 Redis(13 §2 禁用);进程重启会让计数清零,这是 MVP 的取舍。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int refreshPerMinute;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.security.rate-limit.login-per-minute}") int loginPerMinute,
            @Value("${app.security.rate-limit.register-per-minute}") int registerPerMinute,
            @Value("${app.security.rate-limit.refresh-per-minute}") int refreshPerMinute,
            ObjectMapper objectMapper
    ) {
        this.loginPerMinute = loginPerMinute;
        this.registerPerMinute = registerPerMinute;
        this.refreshPerMinute = refreshPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Integer limit = limitFor(path);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIp(request);
        String key = path + "|" + ip;
        long currentMinute = System.currentTimeMillis() / 60_000L;
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        boolean allowed = bucket.allow(limit, currentMinute);
        if (!allowed) {
            writeTooManyRequests(response, request);
            return;
        }
        chain.doFilter(request, response);
    }

    private Integer limitFor(String path) {
        if (path == null) return null;
        if (path.equals("/api/auth/login")) return loginPerMinute;
        if (path.equals("/api/auth/register")) return registerPerMinute;
        if (path.equals("/api/auth/refresh")) return refreshPerMinute;
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<Void> body = ApiResponse.failure(42901, "请求频率超限,请稍后再试", traceId);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 简单滑动分钟桶:每分钟清零一次。 */
    private static final class Bucket {
        private volatile long minute = -1L;
        private final AtomicInteger counter = new AtomicInteger(0);

        boolean allow(int limit, long currentMinute) {
            if (currentMinute != minute) {
                synchronized (this) {
                    if (currentMinute != minute) {
                        counter.set(0);
                        minute = currentMinute;
                    }
                }
            }
            return counter.incrementAndGet() <= limit;
        }
    }
}
