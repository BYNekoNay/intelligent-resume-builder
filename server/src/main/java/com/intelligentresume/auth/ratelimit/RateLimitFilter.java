package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.ErrorCode;
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
 * 认证与重解析端点的内存令牌桶限流(MVP)。
 *
 * <p>按客户端 IP + path 组合分桶,达到阈值时返回 429。覆盖范围：
 * <ul>
 *   <li>认证端点：/api/auth/login、/api/auth/register、/api/auth/refresh</li>
 *   <li>CPU 放大器端点：/api/resume-imports/parse（PDFBox/POI 全内存解析,阈值严格）、
 *       /api/jobs/{id}/parse（JD 本地文本解析,阈值宽松）</li>
 * </ul>
 * 不引入 Redis(13 §2 禁用);进程重启会让计数清零,这是 MVP 的取舍。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int refreshPerMinute;
    private final int resumeImportParsePerMinute;
    private final int jdParsePerMinute;
    private final boolean trustForwardedHeaders;
    private final int maxBuckets;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.security.rate-limit.login-per-minute}") int loginPerMinute,
            @Value("${app.security.rate-limit.register-per-minute}") int registerPerMinute,
            @Value("${app.security.rate-limit.refresh-per-minute}") int refreshPerMinute,
            @Value("${app.security.rate-limit.resume-import-parse-per-minute:6}") int resumeImportParsePerMinute,
            @Value("${app.security.rate-limit.jd-parse-per-minute:15}") int jdParsePerMinute,
            @Value("${app.security.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${app.security.rate-limit.max-buckets:10000}") int maxBuckets,
            ObjectMapper objectMapper
    ) {
        this.loginPerMinute = loginPerMinute;
        this.registerPerMinute = registerPerMinute;
        this.refreshPerMinute = refreshPerMinute;
        this.resumeImportParsePerMinute = resumeImportParsePerMinute;
        this.jdParsePerMinute = jdParsePerMinute;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.maxBuckets = maxBuckets;
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
        evictStaleBuckets(currentMinute);
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
        // 简历解析是重 CPU 端点（PDFBox/POI 全内存解析最大 5MB 文件），阈值给最严
        if (path.equals("/api/resume-imports/parse")) return resumeImportParsePerMinute;
        // JD 解析是轻量本地文本解析，阈值宽松；仅匹配 /api/jobs/{id}/parse 形态，不影响 /api/jobs 其它端点
        if (path.startsWith("/api/jobs/") && path.endsWith("/parse")) return jdParsePerMinute;
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = trustForwardedHeaders ? request.getHeader("X-Forwarded-For") : null;
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private void evictStaleBuckets(long currentMinute) {
        if (buckets.size() < maxBuckets) return;
        buckets.entrySet().removeIf(entry -> entry.getValue().minute < currentMinute - 1);
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<Void> body = ApiResponse.failure(ErrorCode.RATE_LIMITED.code(), "请求频率超限,请稍后再试", traceId);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 固定窗口计数:每个自然分钟清零一次,窗口边界处突发流量可能达到 2 倍阈值。 */
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
