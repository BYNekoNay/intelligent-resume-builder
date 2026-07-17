package com.intelligentresume.export.client;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PDF 服务 HTTP 客户端:POST /render。
 *
 * <p>调用 pdf-service 的渲染接口,获得 PDF 字节数组。
 * 超时、上限、拒绝外部 URL 都在 pdf-service 端强制;这里只做最少量的安全断言。
 */
@Component
public class PdfServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PdfServiceClient.class);
    private static final long MAX_INPUT_BYTES = 524288L;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String serviceToken;
    private final int timeoutSeconds;

    public PdfServiceClient(
            RestTemplateBuilder builder,
            @Value("${app.pdf.service-base-url}") String baseUrl,
            @Value("${app.pdf.service-token}") String serviceToken,
            @Value("${app.pdf.render-timeout-seconds}") int timeoutSeconds
    ) {
        this.restTemplate = builder
                .setConnectTimeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds / 2)))
                .setReadTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
        this.timeoutSeconds = timeoutSeconds;
    }

    public byte[] render(String templateCode, Map<String, Object> payload) {
        assertSafe(payload);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Token", serviceToken);

        Map<String, Object> body = Map.of(
                "templateCode", templateCode,
                "payload", payload
        );
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl + "/render",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    byte[].class);
            byte[] data = response.getBody();
            if (data == null) {
                throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 渲染结果为空");
            }
            return data;
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            log.warn("PDF render rejected: status={} body={}", ex.getStatusCode(),
                    new String(ex.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 服务拒绝渲染: " + ex.getStatusCode());
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new BusinessException(ErrorCode.PDF_FAILURE, "PDF 服务不可达");
        }
    }

    private void assertSafe(Map<String, Object> payload) {
        // 防护:基本大小限制
        String serialized = String.valueOf(payload);
        if (serialized.length() > MAX_INPUT_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出数据超出最大允许大小");
        }
        // 不允许 payload 内出现 file: / http(s): URL 提示词(JSON Resume 标准化已剥离)
        for (String forbidden : new String[]{"file:", "http://", "https://"}) {
            if (serialized.contains(forbidden)) {
                // 容忍 content 字段里出现的纯 URL 文本,这里只挡 file:
                if (forbidden.equals("file:")) {
                    throw new BusinessException(ErrorCode.VALIDATION, "检测到 file: URL,拒绝渲染");
                }
            }
        }
    }
}
