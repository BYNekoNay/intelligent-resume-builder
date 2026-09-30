package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.api.ClientIpResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilter 单元测试。
 *
 * <p>直接调用 {@code doFilterInternal}，验证内存令牌桶的限流行为。
 * 覆盖认证端点与 CPU 放大器端点（resume-imports/parse、jobs/{id}/parse）。
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // login=2/min, register=5/min, refresh=30/min, resume-import-parse=6/min, jd-parse=15/min,
        // 凭证变更=2/min、账号导出=3/min（本测试内的取值）
        filter = new RateLimitFilter(2, 5, 30, 6, 15, 2, 3, new ClientIpResolver(false), 10000, objectMapper);
    }

    @Test
    @DisplayName("第 3 次登录请求返回 429（限额 2 次/分钟）")
    void overLimit_returnsRateLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // 前 2 次放行
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次请求应放行");
        }

        // 第 3 次限流
        MockHttpServletRequest req = loginRequest("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        assertEquals(429, resp.getStatus(), "第 3 次请求应被限流");
        assertTrue(resp.getContentAsString().contains("42901"),
                "响应体应包含错误码 42901");
        // RFC 6585：429 应告知可重试时机（固定窗口剩余秒数 1~60），与 pdf-service 503 的语义一致
        String retryAfter = resp.getHeader("Retry-After");
        assertNotNull(retryAfter, "429 响应应携带 Retry-After 头");
        assertTrue(Long.parseLong(retryAfter) >= 1 && Long.parseLong(retryAfter) <= 60,
                "Retry-After 应为距下一固定窗口的秒数（1~60），实际 " + retryAfter);

        // chain 只被调用了 2 次（前 2 次放行）
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("不同 IP 互不影响")
    void differentIps_independentBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // IP-A 用完 2 次配额
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = loginRequest("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus());
        }

        // IP-A 第 3 次被限流
        MockHttpServletResponse respA = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.1"), respA, chain);
        assertEquals(429, respA.getStatus(), "IP-A 应被限流");

        // IP-B 不受影响
        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.2"), respB, chain);
        assertEquals(200, respB.getStatus(), "IP-B 不应被限流");

        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    @DisplayName("resume-imports/parse 第 7 次请求返回 429（限额 6 次/分钟）")
    void resumeImportParse_overLimit_returnsRateLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 6; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(parseRequest("10.0.0.1", "/api/resume-imports/parse"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次解析应放行");
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.1", "/api/resume-imports/parse"), resp, chain);
        assertEquals(429, resp.getStatus(), "第 7 次解析应被限流");
        assertTrue(resp.getContentAsString().contains("42901"));
    }

    @Test
    @DisplayName("jobs/{id}/parse 第 16 次请求返回 429（限额 15 次/分钟）")
    void jdParse_overLimit_returnsRateLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 15; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/123/parse"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次 JD 解析应放行");
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/123/parse"), resp, chain);
        assertEquals(429, resp.getStatus(), "第 16 次 JD 解析应被限流");
        assertTrue(resp.getContentAsString().contains("42901"));
    }

    @Test
    @DisplayName("限流只命中 /api/jobs/{id}/parse 分桶，/api/jobs 其它端点不受影响")
    void jdParse_bucketDoesNotAffectOtherJobEndpoints() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // 用完 /api/jobs/123/parse 的配额（15 次）
        for (int i = 0; i < 15; i++) {
            filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/123/parse"),
                    new MockHttpServletResponse(), chain);
        }
        // parse 桶已满，第 16 次 429
        MockHttpServletResponse parseResp = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/123/parse"), parseResp, chain);
        assertEquals(429, parseResp.getStatus());

        // /api/jobs/123 本体不在限流范围，不受 parse 桶影响
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/123"), resp, chain);
            assertEquals(200, resp.getStatus(), "/api/jobs/123 不应被限流");
        }

        // 另一个 JD 的 parse 使用独立分桶（key 含 path），也不受 123 的桶影响
        MockHttpServletResponse otherJdResp = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.1", "/api/jobs/456/parse"), otherJdResp, chain);
        assertEquals(200, otherJdResp.getStatus(), "/api/jobs/456/parse 应有独立分桶");

        verify(chain, times(15 + 5 + 1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("百分号转义路径与明文路径共享同一分桶（%6C/%6c 编码的 login 不能绕过限流）")
    void encodedPath_sharesBucketWithPlainPath() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // 明文用掉 2 次配额
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(loginRequest("10.0.0.1"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次请求应放行");
        }

        // 明文第 3 次被限流（基线）
        MockHttpServletResponse plainResp = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.1"), plainResp, chain);
        assertEquals(429, plainResp.getStatus(), "明文第 3 次应被限流");

        // 等价的百分号转义路径（Spring MVC 会解码后路由到同一 login 控制器）必须同样被限流：
        // 修复前 /api/auth/%6Cogin 未命中 path 精确匹配 → 直接放行（真实 HTTP 已实证绕过）
        for (String encoded : new String[]{"/api/auth/%6Cogin", "/api/auth/%6cogin"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", encoded);
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(429, resp.getStatus(), "转义路径 " + encoded + " 应与明文共享分桶");
        }
    }

    @Test
    @DisplayName("矩阵参数（;）路径同样计入分桶，不回退为无限流")
    void semicolonPath_countsTowardBucket() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(loginRequest("10.0.0.1"), resp, chain);
            assertEquals(200, resp.getStatus());
        }

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login;x=1");
        req.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        assertEquals(429, resp.getStatus(), "带矩阵参数的等价路径应被同一分桶限流（fail-closed）");
    }

    @Test
    @DisplayName("凭证变更端点受限且共享分桶：改密 2 次后改邮箱同样 429（交替不能获得双倍预算）")
    void credentialChangeEndpoints_shareBucket() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(postRequest("10.0.0.1", "/api/auth/me/password"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次改密应放行");
        }

        // 修复前该端点不在限流映射内（真实 HTTP 实测无限 401）；共享分桶后改邮箱必须同被限流
        MockHttpServletResponse emailResp = new MockHttpServletResponse();
        filter.doFilter(postRequest("10.0.0.1", "/api/auth/me/email"), emailResp, chain);
        assertEquals(429, emailResp.getStatus(), "改邮箱应与改密共享分桶");
        assertTrue(emailResp.getContentAsString().contains("42901"));

        // 不同 IP 分桶独立
        MockHttpServletResponse otherIpResp = new MockHttpServletResponse();
        filter.doFilter(postRequest("10.0.0.2", "/api/auth/me/email"), otherIpResp, chain);
        assertEquals(200, otherIpResp.getStatus(), "不同 IP 不应受他人凭证桶影响");
    }

    @Test
    @DisplayName("资料更新（PATCH /api/auth/me）不在限流范围，不受凭证桶影响")
    void profileUpdate_notLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(postRequest("10.0.0.1", "/api/auth/me"), resp, chain);
            assertEquals(200, resp.getStatus(), "/api/auth/me 资料更新不应被限流");
        }
    }

    @Test
    @DisplayName("parse 端点不同 IP 分桶独立")
    void parse_differentIps_independentBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 6; i++) {
            filter.doFilter(parseRequest("10.0.0.1", "/api/resume-imports/parse"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse respA = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.1", "/api/resume-imports/parse"), respA, chain);
        assertEquals(429, respA.getStatus(), "IP-A 超配额应被限流");

        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilter(parseRequest("10.0.0.2", "/api/resume-imports/parse"), respB, chain);
        assertEquals(200, respB.getStatus(), "IP-B 不应受 IP-A 影响");
    }

    @Test
    @DisplayName("桶数达到 maxBuckets 后新 key 直接被拒（#43：硬上限,防无界增长）")
    void bucketCapacity_rejectsNewKeys() throws Exception {
        // maxBuckets=2：两个 IP 占满容量后，第三个新 key 无法再建桶
        RateLimitFilter capped = new RateLimitFilter(2, 5, 30, 6, 15, 2, 3, new ClientIpResolver(false), 2, objectMapper);
        FilterChain chain = mock(FilterChain.class);

        assertEquals(200, statusOf(capped, "10.0.0.1", chain), "第 1 个 IP 首次请求应放行");
        assertEquals(200, statusOf(capped, "10.0.0.2", chain), "第 2 个 IP 首次请求应放行");

        MockHttpServletResponse cappedResp = new MockHttpServletResponse();
        capped.doFilter(loginRequest("10.0.0.3"), cappedResp, chain);
        assertEquals(429, cappedResp.getStatus(), "新 key 在容量耗尽后应被拒（fail-closed）");
        assertTrue(cappedResp.getContentAsString().contains("42901"));

        // 已有分桶不受硬上限影响，在其配额内继续放行
        assertEquals(200, statusOf(capped, "10.0.0.1", chain), "已有分桶不应受影响");
    }

    @Test
    @DisplayName("不信任转发头：换着伪造 X-Forwarded-For 也拿不到新分桶（按 remoteAddr 同桶）")
    void untrustedForwardedHeaders_spoofedHeaderCannotBypass() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        assertEquals(200, statusWithXff(filter, "10.0.0.1", "203.0.113.7", chain));
        assertEquals(200, statusWithXff(filter, "10.0.0.1", "198.51.100.9", chain));
        assertEquals(429, statusWithXff(filter, "10.0.0.1", "192.0.2.55", chain),
                "第 3 次即使换新 XFF 值也应被限流（桶按 remoteAddr）");
    }

    @Test
    @DisplayName("信任转发头：按 XFF 最左值分桶，同一代理地址后的不同真实客户端互不影响")
    void trustedForwardedHeaders_bucketsByForwardedClient() throws Exception {
        RateLimitFilter trusted = new RateLimitFilter(2, 5, 30, 6, 15, 2, 3, new ClientIpResolver(true), 10000, objectMapper);
        FilterChain chain = mock(FilterChain.class);

        // 同一 remoteAddr（代理地址）下，客户端 A 用完 2 次配额
        assertEquals(200, statusWithXff(trusted, "172.18.0.4", "203.0.113.7", chain));
        assertEquals(200, statusWithXff(trusted, "172.18.0.4", "203.0.113.7", chain));
        assertEquals(429, statusWithXff(trusted, "172.18.0.4", "203.0.113.7", chain), "客户端 A 超配额应被限流");

        // 客户端 B（同一代理地址）不受影响
        assertEquals(200, statusWithXff(trusted, "172.18.0.4", "198.51.100.9", chain), "客户端 B 应有独立分桶");
    }

    @Test
    @DisplayName("滑动窗口：跨自然分钟边界不能重置配额（固定窗口在边界可稳定拿到 2 倍阈值）")
    void slidingWindow_burstAcrossMinuteBoundary_isBounded() throws Exception {
        // 可控时钟：把时间钉在「距自然分钟结束还有 100ms」处，随后手动跨过边界
        long[] clock = {1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 60_000L) + 59_900L};
        // login 限额 10/min（构造器第 1 个参数）
        RateLimitFilter sliding = new RateLimitFilter(10, 5, 30, 6, 15, 2, 3, new ClientIpResolver(false), 10000, objectMapper) {
            @Override
            long nowMs() {
                return clock[0];
            }
        };
        FilterChain chain = mock(FilterChain.class);

        // 窗口末尾用满 10 次配额
        for (int i = 0; i < 10; i++) {
            assertEquals(200, statusOf(sliding, "10.0.0.1", chain), "第 " + (i + 1) + " 次应放行");
        }

        // 跨过自然分钟边界 200ms：固定窗口会把计数清零、再放行整份配额（10 次 = 2 倍阈值，
        // 攻击者对齐边界即可把 10/min 稳定跑成 20/min）；滑动窗口按上一窗口的剩余比例加权，
        // 最多多放行 1 次（权重近满但非满，故估计值恰低于阈值一次）
        clock[0] += 200;
        int allowedAfterBoundary = 0;
        for (int i = 0; i < 10; i++) {
            if (statusOf(sliding, "10.0.0.1", chain) == 200) {
                allowedAfterBoundary++;
            }
        }
        assertEquals(1, allowedAfterBoundary,
                "跨越边界后应只多放行 1 次（固定窗口缺陷为 10 次，即 2 倍突发）");
    }

    @Test
    @DisplayName("账号导出受限：第 4 次请求 429（单次导出重读全账号数据）")
    void accountExport_overLimit_returnsRateLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(getRequest("10.0.0.1", "/api/auth/export"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次导出应放行");
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(getRequest("10.0.0.1", "/api/auth/export"), resp, chain);
        assertEquals(429, resp.getStatus(), "第 4 次导出应被限流");
        assertTrue(resp.getContentAsString().contains("42901"));

        verify(chain, times(3)).doFilter(any(), any());
    }

    private int statusOf(RateLimitFilter target, String ip, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        target.doFilter(loginRequest(ip), response, chain);
        return response.getStatus();
    }

    /** 同时设置 remoteAddr 与 X-Forwarded-For 的登录请求状态码。 */
    private int statusWithXff(RateLimitFilter target, String remoteAddr, String forwardedFor, FilterChain chain) throws Exception {
        MockHttpServletRequest request = loginRequest(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        target.doFilter(request, response, chain);
        return response.getStatus();
    }

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest parseRequest(String ip, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    /** 任意 POST 路径（用于凭证变更/资料更新等用例）。 */
    private MockHttpServletRequest postRequest(String ip, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    /** 任意 GET 路径（用于账号导出等只读端点）。 */
    private MockHttpServletRequest getRequest(String ip, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(ip);
        return request;
    }
}
