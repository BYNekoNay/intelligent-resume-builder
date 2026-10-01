package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.common.observability.PdfFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PDF 服务客户端。调用 pdf-service 的 /render 端点,带服务令牌认证。
 *
 * <p>使用 RestClient(Spring 6.1+)同步调用,无需引入 webflux。
 * 超时、认证失败、渲染错误均抛 BusinessException(PDF_FAILURE)。
 */
@Component
public class PdfServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PdfServiceClient.class);

    private final RestClient restClient;
    private final RestClient healthRestClient;
    private final String serviceToken;
    private final long maxInputBytes;
    private final long maxOutputBytes;
    private final long healthCacheTtlMs;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;

    /** 健康探测缓存（TTL 内直接复用）：见 {@link #checkHealth()} 的放大面说明。 */
    private final Object healthLock = new Object();
    private volatile long healthCachedAtMs = Long.MIN_VALUE;
    private volatile boolean healthCachedResult;

    public PdfServiceClient(
            @Value("${app.pdf.service-base-url:http://127.0.0.1:3001}") String baseUrl,
            @Value("${app.pdf.service-token:dev-pdf-token-change-me}") String serviceToken,
            @Value("${app.pdf.render-timeout-seconds:50}") int timeoutSeconds,
            @Value("${app.pdf.max-input-bytes:524288}") long maxInputBytes,
            @Value("${app.pdf.max-output-bytes:10485760}") long maxOutputBytes,
            @Value("${app.pdf.health-cache-ttl-ms:5000}") long healthCacheTtlMs,
            AppObservability observability,
            FailureCategoryClassifier failureCategoryClassifier) {
        this.serviceToken = serviceToken;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
        this.healthCacheTtlMs = healthCacheTtlMs;
        this.observability = observability;
        this.failureCategoryClassifier = failureCategoryClassifier;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Service-Token", serviceToken)
                .build();

        SimpleClientHttpRequestFactory healthFactory = new SimpleClientHttpRequestFactory();
        healthFactory.setConnectTimeout(Duration.ofSeconds(1));
        healthFactory.setReadTimeout(Duration.ofSeconds(1));
        this.healthRestClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(healthFactory)
                .build();

        log.info("PdfServiceClient initialized: baseUrl={}, timeout={}s", baseUrl, timeoutSeconds);
    }

    /**
     * 调用 pdf-service /render,返回 PDF 字节。
     *
     * @param templateCode 模板代码(仅 classic)
     * @param payload      简历结构化 JSON
     * @return PDF 文件字节
     * @throws BusinessException PDF_FAILURE(超时/认证失败/渲染错误/输入过大)
     */
    public byte[] render(String templateCode, Map<String, Object> payload) {
        long startedAt = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", templateCode);
        body.put("payload", payload);

        // 输入大小预检
        long estimatedSize = estimateJsonSize(body);
        if (estimatedSize > maxInputBytes) {
            observability.recordPdfRender(templateCode, false, PdfFailureCategory.INPUT_TOO_LARGE,
                    Duration.ofNanos(System.nanoTime() - startedAt));
            throw new BusinessException(ErrorCode.PDF_FAILURE,
                    "导出数据超出最大允许大小 (" + maxInputBytes + " bytes)");
        }

        try {
            byte[] pdfBytes = restClient.post()
                    .uri("/render")
                    .header(TraceIdFilter.TRACE_ID_HEADER, traceId())
                    .body(body)
                    .retrieve()
                    .body(byte[].class);

            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 服务返回空响应");
            }

            // 输出上限：此前 app.pdf.max-output-bytes 在四处配置声明却无消费点（声明即虚构），
            // 渲染返回多大就落盘多大。超限文件不该进入私有存储、也不该被用户下载，
            // 故在落盘前拒绝（docs/03 §9.7「每个任务设置…最大输入和输出大小」）。
            if (pdfBytes.length > maxOutputBytes) {
                log.warn("PDF render output too large: {} bytes > limit {}", pdfBytes.length, maxOutputBytes);
                observability.recordPdfRender(templateCode, false, PdfFailureCategory.OUTPUT_TOO_LARGE,
                        Duration.ofNanos(System.nanoTime() - startedAt));
                throw new BusinessException(ErrorCode.PDF_FAILURE,
                        "导出文件超出最大允许大小 (" + pdfBytes.length + " > " + maxOutputBytes + " bytes)");
            }

            log.debug("PDF render success: {} bytes", pdfBytes.length);
            observability.recordPdfRender(templateCode, true, PdfFailureCategory.NONE,
                    Duration.ofNanos(System.nanoTime() - startedAt));
            return pdfBytes;

        } catch (ResourceAccessException e) {
            PdfFailureCategory category = failureCategoryClassifier.pdf(e);
            log.warn("PDF service transport failure: category={}, exception={}", category, e.getClass().getSimpleName());
            observability.recordPdfRender(templateCode, false, category, Duration.ofNanos(System.nanoTime() - startedAt));
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 服务连接异常");
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            PdfFailureCategory category = failureCategoryClassifier.pdf(e);
            log.warn("PDF service response failure: category={}, status={}", category, e.getStatusCode().value());
            observability.recordPdfRender(templateCode, false, category, Duration.ofNanos(System.nanoTime() - startedAt));
            // 503＝pdf-service 容量/drain 拒绝（可重试）：给用户可读、可重试的文案，而非泛化「渲染失败」
            throw new BusinessException(ErrorCode.PDF_FAILURE,
                    e.getStatusCode().value() == 503 ? "PDF 服务繁忙，请稍后重试" : "PDF 渲染失败");
        } catch (Exception e) {
            PdfFailureCategory category = failureCategoryClassifier.pdf(e);
            log.warn("PDF service call failure: category={}, exception={}", category, e.getClass().getSimpleName());
            observability.recordPdfRender(templateCode, false, category, Duration.ofNanos(System.nanoTime() - startedAt));
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 渲染失败");
        }
    }

    /**
     * Lightweight readiness probe used by the public API health contract.
     * A failed probe means the API remains alive but PDF capability is degraded.
     *
     * <p><b>为何带 TTL 缓存</b>：本探测是真实出站 HTTP 调用，而消费方
     * {@code GET /api/system/health} 是**匿名开放**端点（{@code SecurityConfig} permitAll）。
     * 无缓存时「一个公开请求 = 一次出站调用」：外部可放大成对 pdf-service 的持续探测；
     * pdf-service 不可达时每个请求还要阻塞到连接/读超时（各 1s），持续打即占满 API 请求线程。
     * 缓存把「入站请求速率」与「出站探测速率」解耦（默认 5s 内至多一次探测），
     * 同时保持运维语义（探测新鲜度 ≤ TTL，远小于容器健康检查的 20s 间隔）。
     * 负结果同样缓存：下游故障期间不因重试放大。
     */
    public boolean checkHealth() {
        Boolean fresh = freshHealth();
        if (fresh != null) return fresh;
        // 冷启动/过期后的首个请求执行探测，其余并发请求在锁内复用其结果（避免惊群放大）
        synchronized (healthLock) {
            fresh = freshHealth();
            if (fresh != null) return fresh;
            boolean result = probeHealth();
            healthCachedResult = result;
            healthCachedAtMs = System.currentTimeMillis();
            return result;
        }
    }

    /** TTL 内的缓存值；无缓存或已过期返回 null。先读时间戳再读结果，保证读到的是同一批次。 */
    private Boolean freshHealth() {
        long cachedAt = healthCachedAtMs;
        if (cachedAt == Long.MIN_VALUE) return null;
        return System.currentTimeMillis() - cachedAt < healthCacheTtlMs ? healthCachedResult : null;
    }

    private boolean probeHealth() {
        try {
            Map<?, ?> response = healthRestClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            return response != null && "UP".equals(response.get("status"));
        } catch (Exception e) {
            log.debug("PDF health probe failed: exception={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private String traceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    private long estimateJsonSize(Map<String, Object> body) {
        // 粗略估算:toString 长度 × 2(UTF-8 中文)
        return body.toString().length() * 2L;
    }
}
