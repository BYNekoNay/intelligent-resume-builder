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
    private final String serviceToken;
    private final long maxInputBytes;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;

    public PdfServiceClient(
            @Value("${app.pdf.service-base-url:http://127.0.0.1:3001}") String baseUrl,
            @Value("${app.pdf.service-token:dev-pdf-token-change-me}") String serviceToken,
            @Value("${app.pdf.render-timeout-seconds:15}") int timeoutSeconds,
            @Value("${app.pdf.max-input-bytes:524288}") long maxInputBytes,
            AppObservability observability,
            FailureCategoryClassifier failureCategoryClassifier) {
        this.serviceToken = serviceToken;
        this.maxInputBytes = maxInputBytes;
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
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 渲染失败");
        } catch (Exception e) {
            PdfFailureCategory category = failureCategoryClassifier.pdf(e);
            log.warn("PDF service call failure: category={}, exception={}", category, e.getClass().getSimpleName());
            observability.recordPdfRender(templateCode, false, category, Duration.ofNanos(System.nanoTime() - startedAt));
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 渲染失败");
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
