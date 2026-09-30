package com.intelligentresume.export.service;

import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PDF 服务健康探测的放大面防护（第三十一批）。
 *
 * <p>{@code GET /api/system/health} 是**匿名开放**端点（{@code SecurityConfig} permitAll），
 * 其 {@code pdf-renderer} 检查会发起真实出站 HTTP 调用。无缓存时「一个公开请求 = 一次出站
 * 调用」，外部可把公开探针放大成对 pdf-service 的持续探测；下游不可达时每个请求还会阻塞到
 * 连接/读超时，持续打即占满 API 请求线程。
 *
 * <p>本测试用 JDK 内置 {@link HttpServer} 作桩，**直接统计出站探测次数**（比 mock 更接近真实：
 * 走完整 RestClient 调用链），断言 TTL 内多次调用只产生一次探测、TTL 过后会重新探测，
 * 且负结果同样被缓存（下游故障期间不因重试放大）。
 */
class PdfServiceClientHealthCacheTest {

    private HttpServer server;
    private final AtomicInteger probeCount = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"status\":\"UP\"}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            probeCount.incrementAndGet();
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    @DisplayName("TTL 内多次健康检查只产生一次出站探测（公开探针不可被放大）")
    void repeatedChecksWithinTtlProbeOnce() {
        PdfServiceClient client = client(60_000);

        for (int i = 0; i < 20; i++) {
            assertTrue(client.checkHealth(), "桩服务返回 UP，探测结果应为 true");
        }

        assertEquals(1, probeCount.get(), "TTL 内 20 次健康检查应只触发 1 次出站探测");
    }

    @Test
    @DisplayName("TTL 过后重新探测（保持运维语义：探测新鲜度不超过 TTL）")
    void probingResumesAfterTtl() throws Exception {
        PdfServiceClient client = client(1);

        assertTrue(client.checkHealth());
        assertEquals(1, probeCount.get());

        Thread.sleep(20);
        assertTrue(client.checkHealth());
        assertEquals(2, probeCount.get(), "TTL 过后应重新探测");
    }

    @Test
    @DisplayName("下游故障（5xx）返回 false 且负结果同样被缓存：故障期间不因重试放大")
    void failedProbeIsCachedToo() {
        responseStatus = 503;
        responseBody = "{\"status\":\"DOWN\"}";
        PdfServiceClient client = client(60_000);

        for (int i = 0; i < 10; i++) {
            assertFalse(client.checkHealth(), "下游 5xx 应报告能力降级");
        }

        assertEquals(1, probeCount.get(), "负结果也应在 TTL 内复用，避免故障期间持续外呼");
    }

    private PdfServiceClient client(long healthCacheTtlMs) {
        AppObservability observability = new AppObservability(new SimpleMeterRegistry(),
                mock(AiTaskRepository.class), mock(ExportTaskRepository.class));
        return new PdfServiceClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", 5, 524288L, healthCacheTtlMs,
                observability, new FailureCategoryClassifier());
    }
}
