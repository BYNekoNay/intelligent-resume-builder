package com.intelligentresume.export.service;

import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * pdf-service 容量拒绝（503 + {@code Retry-After}）的语义（B5-2）。
 *
 * <p>回归的断链：pdf-service 在队列打满 / drain 时返回 {@code 503 + Retry-After: 2}
 * （{@code pdf-service/src/server.js}），明确表达了「等 2 秒再来同样的请求就能成功」；
 * 而 {@link PdfServiceClient} 之前把它压平成普通 {@code PDF_FAILURE}，
 * {@code ExportTaskWorker} 随即把任务判为 FAILED —— 服务端给了等待时长，执行侧完全没用。
 *
 * <p>用 JDK 内置 {@link HttpServer} 作桩（与 {@code PdfServiceClientOutputBoundTest} 同法）：
 * 走完整 RestClient 调用链，比 mock 更接近真实，能同时验证「响应头真的被读到」。
 */
class PdfServiceClientCapacityTest {

    private HttpServer server;
    private volatile int status = 503;
    private volatile String retryAfterHeader;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/render", exchange -> {
            if (retryAfterHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            byte[] body = "{\"code\":50301,\"message\":\"capacity\",\"retryable\":true}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    @DisplayName("503 带 Retry-After: 7 → 抛容量异常并按 7 秒重排（而不是判任务失败）")
    void capacityRejectionCarriesRetryAfter() {
        retryAfterHeader = "7";

        PdfCapacityException error = assertThrows(PdfCapacityException.class, () -> client().render("classic", payload()));

        assertEquals(ErrorCode.PDF_FAILURE, error.getErrorCode());
        assertEquals(7, error.getRetryAfterSeconds());
        assertEquals("PDF 服务繁忙，请稍后重试", error.getMessage());
    }

    @Test
    @DisplayName("503 不带 Retry-After → 用兜底等待秒数，仍是容量异常")
    void capacityRejectionWithoutHeaderUsesDefault() {
        retryAfterHeader = null;

        PdfCapacityException error = assertThrows(PdfCapacityException.class, () -> client().render("classic", payload()));

        assertEquals(PdfCapacityException.DEFAULT_RETRY_AFTER_SECONDS, error.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("Retry-After 写成 http-date / 非法值 → 不猜，用兜底秒数")
    void unparsableRetryAfterFallsBack() {
        retryAfterHeader = "Wed, 21 Oct 2026 07:28:00 GMT";

        PdfCapacityException error = assertThrows(PdfCapacityException.class, () -> client().render("classic", payload()));

        assertEquals(PdfCapacityException.DEFAULT_RETRY_AFTER_SECONDS, error.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("Retry-After 离谱地大 → 夹到上限，不让任务排到用户早已离开之后")
    void oversizedRetryAfterIsClamped() {
        retryAfterHeader = "3600";

        PdfCapacityException error = assertThrows(PdfCapacityException.class, () -> client().render("classic", payload()));

        assertEquals(PdfServiceClient.MAX_RETRY_AFTER_SECONDS, error.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("非 503 的失败仍是普通失败（不得被当成可重试，否则坏输入会被无限重排）")
    void nonCapacityFailureStaysOrdinary() {
        status = 500;

        BusinessException error = assertThrows(BusinessException.class, () -> client().render("classic", payload()));

        assertFalse(error instanceof PdfCapacityException, "500 不应被当作容量拒绝（否则坏输入会被无限重排）");
        assertEquals("PDF 渲染失败", error.getMessage());
    }

    private Map<String, Object> payload() {
        return Map.of("basics", Map.of("name", "张三"));
    }

    private PdfServiceClient client() {
        AppObservability observability = new AppObservability(new SimpleMeterRegistry(),
                mock(AiTaskRepository.class), mock(ExportTaskRepository.class));
        return new PdfServiceClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", 5, 524288L, 10485760L, 5000L,
                observability, new FailureCategoryClassifier());
    }
}
