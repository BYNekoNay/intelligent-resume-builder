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

/**
 * 认证与重解析端点的内存滑动窗口限流(MVP)。
 *
 * <p>按客户端 IP + path 组合分桶,达到阈值时返回 429。覆盖范围：
 * <ul>
 *   <li>认证端点：/api/auth/login、/api/auth/register、/api/auth/refresh</li>
 *   <li>凭证变更端点：/api/auth/me/password、/api/auth/me/email——两者都校验「当前密码」，
 *       属口令验证面；不限流时可用被盗 access token 无限试密码（绕过登录的 10/min），
 *       且两端点共享同一分桶，避免在改密/改邮箱之间交替获得双倍预算</li>
 *   <li>CPU 放大器端点：/api/resume-imports/parse（PDFBox/POI 全内存解析,阈值严格）、
 *       /api/jobs/{id}/parse（JD 本地文本解析,阈值宽松）</li>
 *   <li>数据放大器端点：/api/auth/export——单次请求把本人各业务域数据整体重读并序列化，
 *       成本随账号数据量线性增长（实测 200 条 62KB 资料的账号单次响应 11.9MB / ~230ms），
 *       而导出是低频操作，不设上限即可用单个会话把 API 的 DB 与出站带宽打满</li>
 * </ul>
 * 不引入 Redis(13 §2 禁用);进程重启会让计数清零,这是 MVP 的取舍。
 * 窗口语义是**滑动窗口**而非固定窗口(自然分钟):每个分桶保留「当前窗口 + 上一窗口」两个计数,
 * 估计值按上一窗口的剩余比例加权。固定窗口在自然分钟边界会把计数清零,攻击者对齐边界即可
 * 再吃满整份配额(默认 10/min 的登录限流实测可稳定跑成 20/min);滑动窗口把该突发压到最多
 * 1 次(权重近似误差),不再随阈值放大。
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

    /** 限流窗口长度：滑动窗口按该长度对齐（60s，与「每分钟」阈值同单位）。 */
    static final long WINDOW_MS = 60_000L;

    /** 窗口长度（秒）：无分桶可推算退避时的提示值。 */
    private static final long WINDOW_SECONDS = WINDOW_MS / 1000L;

    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int refreshPerMinute;
    private final int resumeImportParsePerMinute;
    private final int jdParsePerMinute;
    private final int changeCredentialPerMinute;
    private final int accountExportPerMinute;
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
            @Value("${app.security.rate-limit.account-export-per-minute:3}") int accountExportPerMinute,
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
        this.accountExportPerMinute = accountExportPerMinute;
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
        long now = nowMs();
        evictStaleBuckets(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            // 硬上限（#43）：达到 maxBuckets 且无可清理的过期桶时拒绝新 key（fail-closed），
            // 避免海量唯一 IP 在同一窗口内把 map 顶成无界增长。
            // 并发下 size 检查与写入之间有微小竞态，最多超出并发线程数，属可接受上界。
            if (buckets.size() >= maxBuckets) {
                // 此处无分桶可推算退避，按窗口上限提示；后续请求会清理过期桶后恢复放行
                writeTooManyRequests(response, request, WINDOW_SECONDS);
                return;
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        }
        if (!bucket.allow(limit, now)) {
            writeTooManyRequests(response, request, bucket.retryAfterSeconds(limit, now));
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
        // 账号导出是「数据放大器」：单次请求把本人各业务域数据整体重读并序列化为一个 JSON 文档，
        // 成本随账号数据量线性增长且无内部分页/上限。导出属低频操作（换设备/备份时偶尔下载），
        // 故阈值给最严一档；若下次请求仍在同一窗口内，客户端应读 Retry-After 退避。
        if (path.equals("/api/auth/export")) return accountExportPerMinute;
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

    private void evictStaleBuckets(long nowMs) {
        if (buckets.size() < maxBuckets) return;
        buckets.entrySet().removeIf(entry -> entry.getValue().isStale(nowMs));
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request, long retryAfterSeconds)
            throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<Void> body = ApiResponse.failure(ErrorCode.RATE_LIMITED.code(), "请求频率超限,请稍后再试", traceId);
        response.setStatus(429);
        // Retry-After：距下一次配额可用的秒数（滑动窗口按剩余权重推算，取值 1~60）。RFC 6585 建议
        // 429 告知可重试时机，与 pdf-service 503 的 Retry-After 语义一致；缺少该头时客户端只能
        // 盲目重试并继续打满窗口。注意不能沿用「距下一自然分钟」——滑动窗口在窗口滚动后上一窗口
        // 仍按接近 100% 权重计入，滚动瞬间重试仍会被拒，那样的提示是错的。
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
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
