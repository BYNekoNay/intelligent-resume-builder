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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PDF 导出输出上限的执行点（第五十二批）。
 *
 * <p>背景：`app.pdf.max-output-bytes`（默认 10MB）在四处配置声明（`application.yml` /
 * `application-local-h2.yml` / `application-test.yml` / `server/.env.example`）、并被
 * `UploadPathContractTest` 与 `web/src/api/export.ts` 当作「导出下载的响应体上限」引用，
 * 但修复前**没有任何消费点**：渲染返回多大就落盘多大（`ExportStorageService.store` 无大小判据），
 * 与 `docs/03` §9.7「每个任务设置…最大输入和输出大小」不符。
 *
 * <p>本测试用 JDK 内置 {@link HttpServer} 作桩（与健康探测缓存同法：走完整 RestClient 调用链，
 * 比 mock 更接近真实），断言：超限响应被拒（可读文案 + PDF_FAILURE），恰好等于上限的响应放行
 * （边界含等于，避免把「正好 10MB」的合法导出误判为失败）。
 */
class PdfServiceClientOutputBoundTest {

    private static final long OUTPUT_LIMIT = 1024;

    private HttpServer server;
    private volatile byte[] responseBody = new byte[0];

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/render", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    @DisplayName("渲染结果超过 max-output-bytes 时拒绝并把可读原因交给调用方")
    void oversizedRenderIsRejected() {
        responseBody = new byte[(int) OUTPUT_LIMIT + 1];
        PdfServiceClient client = client(OUTPUT_LIMIT);

        BusinessException error = assertThrows(BusinessException.class,
                () -> client.render("classic", java.util.Map.of("basics", java.util.Map.of("name", "张三"))));

        assertEquals(ErrorCode.PDF_FAILURE, error.getErrorCode());
        assertTrue(error.getMessage().contains("超出最大允许大小"),
                "超限原因必须可读（实际：" + error.getMessage() + "）");
    }

    @Test
    @DisplayName("恰好等于上限的渲染放行（边界含等于）")
    void renderAtExactLimitIsAccepted() {
        responseBody = new byte[(int) OUTPUT_LIMIT];
        PdfServiceClient client = client(OUTPUT_LIMIT);

        byte[] pdf = client.render("classic", java.util.Map.of("basics", java.util.Map.of("name", "张三")));

        assertEquals(OUTPUT_LIMIT, pdf.length);
    }

    private PdfServiceClient client(long maxOutputBytes) {
        AppObservability observability = new AppObservability(new SimpleMeterRegistry(),
                mock(AiTaskRepository.class), mock(ExportTaskRepository.class));
        return new PdfServiceClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", 5, 524288L, maxOutputBytes, 5000L,
                observability, new FailureCategoryClassifier());
    }
}