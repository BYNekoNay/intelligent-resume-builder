package com.intelligentresume.auth.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.api.ClientIpResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 限流**覆盖面**与**分桶不可绕过**的行为级门禁。
 *
 * <p>这些用例都来自真实缺口，不是假想：
 * <ol>
 *   <li>JD 解析此前按具体路径分桶 → 阈值被放大成 {@code 15 × JD 数}/分钟，换个 id 就绕过；</li>
 *   <li>{@code /api/exports/pdf}、{@code /api/ats/check}、{@code /api/ai/**} 长期完全没有频率闸，
 *       只有「每人每日配额」，一个人可以在一分钟内把整天配额打满；</li>
 *   <li>没有并发用例：顺序循环测不出「突发下是否超发」，而这正是限流器的核心属性。</li>
 * </ol>
 */
class RateLimitCoverageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RateLimitFilter filter(java.util.function.Consumer<RateLimitProperties> customizer) {
        RateLimitProperties properties = new RateLimitProperties();
        customizer.accept(properties);
        return new RateLimitFilter(new RateLimitRegistry(properties), properties,
                new ClientIpResolver(false), objectMapper);
    }

    @Test
    @DisplayName("JD 解析按规则组分桶：换 JD id 不能拿到新预算")
    void jdParse_bucketIsSharedAcrossDifferentJobIds() throws Exception {
        RateLimitFilter filter = filter(props -> props.setJdParsePerMinute(3));
        FilterChain chain = mock(FilterChain.class);

        // 三个**不同** JD 各解析一次，正好用满 3 次预算
        for (int id = 1; id <= 3; id++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(post("10.0.0.1", "/api/jobs/" + id + "/parse"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + id + " 个 JD 的首次解析应放行");
        }

        // 第 4 个 JD 仍应被限流：分桶键是规则组，不是 URL
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.1", "/api/jobs/4/parse"), resp, chain);
        assertEquals(429, resp.getStatus(),
                "按 URL 分桶会让阈值随 JD 数量线性放大（旧实现 15×N/min），必须共享规则组桶");
        assertEquals("jd-parse", resp.getHeader(RateLimitFilter.RATE_LIMIT_RULE_HEADER));
    }

    @Test
    @DisplayName("兜底档覆盖此前完全没有频率闸的写端点（POST /api/resumes）")
    void writeDefault_boundsPreviouslyUnlimitedEndpoint() throws Exception {
        RateLimitFilter filter = filter(props -> props.setWriteDefaultPerMinute(2));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(post("10.0.0.1", "/api/resumes"), resp, chain);
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次写操作应放行");
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.1", "/api/resumes"), resp, chain);
        assertEquals(429, resp.getStatus(), "结构上不允许存在无上限的写端点");
        assertEquals(RateLimitRegistry.WRITE_DEFAULT_ID,
                resp.getHeader(RateLimitFilter.RATE_LIMIT_RULE_HEADER));
    }

    @Test
    @DisplayName("兜底档只管写：读端点不消耗写预算")
    void writeDefault_doesNotLimitReads() throws Exception {
        RateLimitFilter filter = filter(props -> props.setWriteDefaultPerMinute(1));
        FilterChain chain = mock(FilterChain.class);

        // 先把唯一的写预算用掉
        filter.doFilter(post("10.0.0.1", "/api/resumes"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.1", "/api/resumes"), blocked, chain);
        assertEquals(429, blocked.getStatus(), "写预算已耗尽");

        // 同一 IP 的读请求不受影响
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(request("GET", "10.0.0.1", "/api/resumes"), resp, chain);
            assertEquals(200, resp.getStatus(), "GET 不应被写兜底档限流");
        }
    }

    @Test
    @DisplayName("AI 生成档跨端点共享：/api/ai/tasks 用满后 inline-optimize 同样 429")
    void aiGenerate_sharedAcrossEndpoints() throws Exception {
        RateLimitFilter filter = filter(props -> props.setAiGeneratePerMinute(2));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            filter.doFilter(post("10.0.0.1", "/api/ai/tasks"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.1", "/api/ai/inline-optimize"), resp, chain);
        assertEquals(429, resp.getStatus(),
                "AI 各端点若各自分桶，轮换端点即可绕过突发闸");
        assertEquals("ai-generate", resp.getHeader(RateLimitFilter.RATE_LIMIT_RULE_HEADER));

        // 另一个 IP 不受影响（说明限制来自分桶而不是全局计数）
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.2", "/api/ai/inline-optimize"), other, chain);
        assertEquals(200, other.getStatus(), "不同 IP 应有独立预算");
    }

    @Test
    @DisplayName("PDF 渲染与 ATS 体检各自受档位限制")
    void pdfRenderAndAtsCheck_areLimited() throws Exception {
        RateLimitFilter filter = filter(props -> {
            props.setPdfRenderPerMinute(1);
            props.setAtsCheckPerMinute(1);
        });
        FilterChain chain = mock(FilterChain.class);

        assertEquals(200, post(filter, chain, "/api/exports/pdf").getStatus());
        MockHttpServletResponse pdfBlocked = post(filter, chain, "/api/exports/pdf");
        assertEquals(429, pdfBlocked.getStatus(), "PDF 渲染必须受限（每次占一个 Chromium 上下文）");
        assertEquals("pdf-render", pdfBlocked.getHeader(RateLimitFilter.RATE_LIMIT_RULE_HEADER));

        assertEquals(200, post(filter, chain, "/api/ats/check").getStatus());
        MockHttpServletResponse atsBlocked = post(filter, chain, "/api/ats/check");
        assertEquals(429, atsBlocked.getStatus(), "ATS 体检必须受限（CPU + 可能启动 AI 任务）");
        assertEquals("ats-check", atsBlocked.getHeader(RateLimitFilter.RATE_LIMIT_RULE_HEADER));
    }

    @Test
    @DisplayName("并发突发不超发：20 个并发请求、限额 5，恰好放行 5 个")
    void concurrentBurst_neverExceedsLimit() throws Exception {
        RateLimitFilter filter = filter(props -> props.setAtsCheckPerMinute(5));
        FilterChain chain = mock(FilterChain.class);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MockHttpServletResponse resp = new MockHttpServletResponse();
                    try {
                        filter.doFilter(post("10.0.0.1", "/api/ats/check"), resp, chain);
                    } catch (Exception cause) {
                        throw new IllegalStateException(cause);
                    }
                    if (resp.getStatus() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            // 同一时刻起跑，制造真实窗口内竞争（顺序 for 循环测不出这个属性）
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(5, allowed.get(), "并发下放行数必须恰好等于阈值（多放行=限流失效）");
        assertEquals(threads - 5, rejected.get(), "其余请求必须 429");
    }

    private MockHttpServletResponse post(RateLimitFilter target, FilterChain chain, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        target.doFilter(post("10.0.0.1", path), response, chain);
        return response;
    }

    private MockHttpServletRequest post(String ip, String path) {
        return request("POST", ip, path);
    }

    private MockHttpServletRequest request(String method, String ip, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }
}
