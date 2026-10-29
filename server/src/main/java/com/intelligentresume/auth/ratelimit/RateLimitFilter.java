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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存滑动窗口限流：**只负责桶机制**，不决定「谁受限」。
 *
 * <p>哪些路径受限、分桶到哪一组、阈值多少、为什么限，全部由 {@link RateLimitRegistry} 表达；
 * 本类只做三件事：解析客户端 IP、按「规则组 | IP」取桶、超限时写 429 + {@code Retry-After}。
 * 这样拆分的原因写在 {@link RateLimitRegistry} 的类注释里（此前 if 链导致覆盖面长期漏端点和分桶绕过）。
 *
 * <p>分桶维度是**规则组 + 客户端 IP**，不是 URL：{@code /api/jobs/1/parse} 与
 * {@code /api/jobs/2/parse} 命中同一条规则、同一组，共享一份预算——按 URL 分桶会随 URL 基数
 * 线性放大阈值，等于没限流。
 *
 * <p>不引入 Redis(13 §2 禁用)；进程重启会让计数清零，这是 MVP 的取舍。
 * 窗口语义是**滑动窗口**而非固定窗口(自然分钟)：每个分桶保留「当前窗口 + 上一窗口」两个计数，
 * 估计值按上一窗口的剩余比例加权。固定窗口在自然分钟边界会把计数清零，攻击者对齐边界即可
 * 再吃满整份配额（默认 10/min 的登录限流实测可稳定跑成 20/min）；滑动窗口把该突发压到最多
 * 1 次（权重近似误差），不再随阈值放大。
 *
 * <p>客户端 IP 由 {@link ClientIpResolver} 统一解析（与会话审计同一语义）：默认不信任
 * X-Forwarded-For；生产开启信任时要求最外层代理覆写该头。
 *
 * <p>{@code maxBuckets} 是硬上限(#43)：达到后新分桶直接 429(fail-closed)，
 * 防止海量唯一 IP 把分桶 map 顶到无界增长；已有分桶不受影响。
 *
 * <p>已知边界：分桶维度只有 IP，没有用户。同一 NAT/公司出口下的多人会共享预算；
 * 反过来，持有多个出口的攻击者可以绕过——账号级预算目前由 AI 日配额承担，
 * 按用户的频率闸需要把限流挪到认证之后（会牺牲对 401 洪水的保护），留待后续批次决策。
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

    /** 限流窗口长度：滑动窗口按该长度对齐（60s，与「每分钟」阈值同单位）。 */
    static final long WINDOW_MS = 60_000L;

    /** 窗口长度（秒）：无分桶可推算退避时的提示值。 */
    private static final long WINDOW_SECONDS = WINDOW_MS / 1000L;

    /** 429 响应回显命中的限流规则 id（排障用；不含阈值与内部路径）。 */
    public static final String RATE_LIMIT_RULE_HEADER = "X-RateLimit-Rule";

    private final RateLimitRegistry registry;
    private final ClientIpResolver clientIpResolver;
    private final int maxBuckets;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            RateLimitRegistry registry,
            RateLimitProperties properties,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper
    ) {
        this.registry = registry;
        this.clientIpResolver = clientIpResolver;
        this.maxBuckets = properties.getMaxBuckets();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATH_HELPER.getPathWithinApplication(request);
        RateLimitRule rule = registry.resolve(request.getMethod(), path);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIpResolver.resolve(request);
        String key = rule.bucketGroup() + "|" + ip;
        long now = nowMs();
        evictStaleBuckets(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            // 硬上限（#43）：达到 maxBuckets 且无可清理的过期桶时拒绝新 key（fail-closed），
            // 避免海量唯一 IP 在同一窗口内把 map 顶成无界增长。
            // 并发下 size 检查与写入之间有微小竞态，最多超出并发线程数，属可接受上界。
            if (buckets.size() >= maxBuckets) {
                // 此处无分桶可推算退避，按窗口上限提示；后续请求会清理过期桶后恢复放行
                writeTooManyRequests(response, request, WINDOW_SECONDS, null);
                return;
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        }
        int limit = rule.limitPerMinute();
        if (!bucket.allow(limit, now)) {
            writeTooManyRequests(response, request, bucket.retryAfterSeconds(limit, now), rule.id());
            return;
        }
        chain.doFilter(request, response);
    }

    private void evictStaleBuckets(long nowMs) {
        if (buckets.size() < maxBuckets) return;
        buckets.entrySet().removeIf(entry -> entry.getValue().isStale(nowMs));
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request,
                                      long retryAfterSeconds, String ruleId)
            throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<Void> body = ApiResponse.failure(ErrorCode.RATE_LIMITED.code(), "请求频率超限,请稍后再试", traceId);
        response.setStatus(429);
        // Retry-After：距下一次配额可用的秒数（滑动窗口按剩余权重推算，取值 1~60）。RFC 6585 建议
        // 429 告知可重试时机，与 pdf-service 503 的 Retry-After 语义一致；缺少该头时客户端只能
        // 盲目重试并继续打满窗口。注意不能沿用「距下一自然分钟」——滑动窗口在窗口滚动后上一窗口
        // 仍按接近 100% 权重计入，滚动瞬间重试仍会被拒，那样的提示是错的。
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        if (ruleId != null) {
            // 排障锚点：线上出现 429 时，没有它只能靠阈值反推命中了哪条规则。
            // 只回显规则 id（不是内部路径或阈值），不构成信息泄露。
            response.setHeader(RATE_LIMIT_RULE_HEADER, ruleId);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 当前时间（UTC epoch 毫秒）。测试覆写此方法以驱动窗口边界。 */
    long nowMs() {
        return System.currentTimeMillis();
    }

    /**
     * 滑动窗口计数：对齐到 {@code WINDOW_MS} 边界的「当前窗口 + 上一窗口」两个计数。
     *
     * <p>估计值 = 上一窗口计数 × 当前窗口剩余比例 + 当前窗口计数。相较固定窗口「每个自然分钟
     * 清零」，窗口切换瞬间上一窗口仍按接近 100% 的权重计入，故对齐自然分钟边界无法再拿到整份
     * 额外配额（旧实现实测 10/min 可稳定跑出 20/min = 窗口边界两连击）。剩余误差来自「上一窗口
     * 请求均匀分布」的假设：切换瞬间最多多放行 1 次（不再随阈值放大）。
     */
    private static final class Bucket {
        /** 当前窗口起点（对齐 WINDOW_MS）；-1 表示尚未初始化。 */
        private long windowStartMs = -1L;
        /** 当前窗口放行数。 */
        private int currentCount;
        /** 上一窗口放行数（按剩余比例加权计入估计值）。 */
        private int previousCount;

        synchronized boolean allow(int limit, long nowMs) {
            rollWindow(nowMs);
            if (estimatedCount(nowMs) >= limit) {
                return false;
            }
            currentCount++;
            return true;
        }

        /** 距下一次配额可用的秒数，取值 1~60（供 429 的 Retry-After）。 */
        synchronized long retryAfterSeconds(int limit, long nowMs) {
            rollWindow(nowMs);
            long waitWithinWindowMs = Long.MAX_VALUE;
            int headroom = limit - currentCount;
            if (headroom > 0 && previousCount > 0) {
                // 上一窗口权重衰减到 headroom 以下即可放行：prev × (1 - d/W) < headroom
                double decayToMs = (double) WINDOW_MS * (1d - (double) headroom / previousCount);
                waitWithinWindowMs = Math.max(0L, windowStartMs + (long) Math.ceil(decayToMs) - nowMs);
            }
            long toNextWindowMs = WINDOW_MS - (nowMs - windowStartMs);
            // 窗口滚动后估计值 = 当前窗口计数（整体转为权重 100% 的上一窗口）：
            // 未满额则滚动即可放行，已满额需 1ms 让权重开始衰减
            long waitAfterRollMs = currentCount >= limit ? toNextWindowMs + 1L : toNextWindowMs;
            long seconds = (long) Math.ceil(Math.min(waitWithinWindowMs, waitAfterRollMs) / 1000d);
            return Math.min(60L, Math.max(1L, seconds));
        }

        /** 两个窗口以前的计数已不再影响估计值，可安全回收（等价于新建桶）。 */
        synchronized boolean isStale(long nowMs) {
            return windowStartMs >= 0 && nowMs - windowStartMs >= 2 * WINDOW_MS;
        }

        private double estimatedCount(long nowMs) {
            double remaining = 1d - (double) (nowMs - windowStartMs) / WINDOW_MS;
            return previousCount * remaining + currentCount;
        }

        private void rollWindow(long nowMs) {
            long windowStart = nowMs - Math.floorMod(nowMs, WINDOW_MS);
            if (windowStart == windowStartMs) {
                return;
            }
            // 仅当恰好前进一个窗口时上一窗口计数才有意义；跳过多个窗口（或首次使用）等价于全新桶
            previousCount = (windowStartMs >= 0 && windowStart - windowStartMs == WINDOW_MS) ? currentCount : 0;
            currentCount = 0;
            windowStartMs = windowStart;
        }
    }
}
