package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.ClientIpResolver;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证与重解析端点的内存令牌桶限流(MVP)。
 *
 * <p>按客户端 IP + path 组合分桶,达到阈值时返回 429。覆盖范围：
 * <ul>
 *   <li>认证端点：/api/auth/login、/api/auth/register、/api/auth/refresh</li>
 *   <li>凭证变更端点：/api/auth/me/password、/api/auth/me/email——两者都校验「当前密码」，
 *       属口令验证面；不限流时可用被盗 access token 无限试密码（绕过登录的 10/min），
 *       且两端点共享同一分桶，避免在改密/改邮箱之间交替获得双倍预算</li>
 *   <li>CPU 放大器端点：/api/resume-imports/parse（PDFBox/POI 全内存解析,阈值严格）、
 *       /api/jobs/{id}/parse（JD 本地文本解析,阈值宽松）</li>
 * </ul>
 * 不引入 Redis(13 §2 禁用);进程重启会让计数清零,这是 MVP 的取舍。
 * 客户端 IP 由 {@link ClientIpResolver} 统一解析（与会话审计同一语义）：默认不信任
 * X-Forwarded-For；生产开启信任时要求最外层代理覆写该头（见该类 Javadoc 的可信链前提）。
 * {@code maxBuckets} 是硬上限(#43):达到后新分桶直接 429(fail-closed),
 * 防止海量唯一 IP 把分桶 map 顶到无界增长;已有分桶不受影响。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * 与 Spring MVC 相同的路径解析语义：解码百分号转义、去掉矩阵参数（;）。
     *
     * <p>此前直接用 {@code request.getRequestURI()} 做精确匹配，而 MVC 是**解码后**
     * 路由：{@code POST /api/auth/%6Cogin}（%6C=小写 l）会命中 login 控制器，却匹配
     * 不上限流的字面量路径——真实 HTTP 探针实证为**限流绕过**（同一 IP 第 3 次明文
     * 请求 429，转义路径仍 401 直达控制器）；refresh/register 与两个解析端点同族受险。
     * 转义/矩阵参数只会被计到同一分桶（fail-closed），不会放宽限流。
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int refreshPerMinute;
    private final int resumeImportParsePerMinute;
    private final int jdParsePerMinute;
    private final int changeCredentialPerMinute;
    private final ClientIpResolver clientIpResolver;
    private final int maxBuckets;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.security.rate-limit.login-per-minute}") int loginPerMinute,
            @Value("${app.security.rate-limit.register-per-minute}") int registerPerMinute,
            @Value("${app.security.rate-limit.refresh-per-minute}") int refreshPerMinute,
            @Value("${app.security.rate-limit.resume-import-parse-per-minute:6}") int resumeImportParsePerMinute,
            @Value("${app.security.rate-limit.jd-parse-per-minute:15}") int jdParsePerMinute,
            @Value("${app.security.rate-limit.change-credential-per-minute:5}") int changeCredentialPerMinute,
            ClientIpResolver clientIpResolver,
            @Value("${app.security.rate-limit.max-buckets:10000}") int maxBuckets,
            ObjectMapper objectMapper
    ) {
        this.loginPerMinute = loginPerMinute;
        this.registerPerMinute = registerPerMinute;
        this.refreshPerMinute = refreshPerMinute;
        this.resumeImportParsePerMinute = resumeImportParsePerMinute;
        this.jdParsePerMinute = jdParsePerMinute;
        this.changeCredentialPerMinute = changeCredentialPerMinute;
        this.clientIpResolver = clientIpResolver;
        this.maxBuckets = maxBuckets;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATH_HELPER.getPathWithinApplication(request);
        Integer limit = limitFor(path);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIpResolver.resolve(request);
        String key = bucketGroup(path) + "|" + ip;
        long currentMinute = System.currentTimeMillis() / 60_000L;
        evictStaleBuckets(currentMinute);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            // 硬上限（#43）：达到 maxBuckets 且无可清理的过期桶时拒绝新 key（fail-closed），
            // 避免海量唯一 IP 在同一分钟内把 map 顶成无界增长。
            // 并发下 size 检查与写入之间有微小竞态，最多超出并发线程数，属可接受上界。
            if (buckets.size() >= maxBuckets) {
                writeTooManyRequests(response, request);
                return;
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        }
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
        // 凭证变更是「当前密码」验证面：不限流时可用被盗 access token 无限试密码
        // （绕过登录的 10/min）。实测（第二十七批）：配置阈值 2 下连续 4 次请求全 401、无 429。
        if (isCredentialChange(path)) return changeCredentialPerMinute;
        // 简历解析是重 CPU 端点（PDFBox/POI 全内存解析最大 5MB 文件），阈值给最严
        if (path.equals("/api/resume-imports/parse")) return resumeImportParsePerMinute;
        // JD 解析是轻量本地文本解析，阈值宽松；仅匹配 /api/jobs/{id}/parse 形态，不影响 /api/jobs 其它端点
        if (path.startsWith("/api/jobs/") && path.endsWith("/parse")) return jdParsePerMinute;
        return null;
    }

    private static boolean isCredentialChange(String path) {
        return path.equals("/api/auth/me/password") || path.equals("/api/auth/me/email");
    }

    /**
     * 分桶分组：凭证变更两端点共享一组——若按路径各自分桶，攻击者可在改密/改邮箱之间
     * 交替请求获得双倍预算；其它端点维持按路径独立分桶（/api/jobs/{id}/parse 等按具体路径隔离）。
     */
    private static String bucketGroup(String path) {
        return isCredentialChange(path) ? "/api/auth/me:credential" : path;
    }

    private void evictStaleBuckets(long currentMinute) {
        if (buckets.size() < maxBuckets) return;
        buckets.entrySet().removeIf(entry -> entry.getValue().minute < currentMinute - 1);
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<Void> body = ApiResponse.failure(ErrorCode.RATE_LIMITED.code(), "请求频率超限,请稍后再试", traceId);
        response.setStatus(429);
        // Retry-After：固定窗口（自然分钟）剩余秒数 1~60。RFC 6585 建议 429 告知可重试时机，
        // 与 pdf-service 503 的 Retry-After 语义一致；缺少该头时客户端只能盲目重试并继续打满窗口。
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(secondsUntilNextWindow()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 距下一个固定窗口（自然分钟）开始的秒数，取值 1~60。 */
    static long secondsUntilNextWindow() {
        return 60 - (System.currentTimeMillis() / 1000L) % 60;
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
