package com.intelligentresume.common.observability;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;

@Component
public class FailureCategoryClassifier {

    public AiFailureCategory ai(Throwable error) {
        if (error instanceof BusinessException business) {
            if (business.getErrorCode() == ErrorCode.CONSENT_REQUIRED) return AiFailureCategory.CONSENT_REVOKED;
            if (business.getErrorCode() == ErrorCode.RATE_LIMITED) return AiFailureCategory.QUOTA_EXHAUSTED;
        }
        if (error instanceof ResourceAccessException) {
            return contains(error.getMessage(), "timeout", "timed out", "read timed")
                    ? AiFailureCategory.TIMEOUT : AiFailureCategory.CONNECTION;
        }
        if (error instanceof RestClientResponseException response) {
            return aiStatus(response.getStatusCode());
        }
        return aiMessage(error == null ? null : error.getMessage());
    }

    public AiFailureCategory aiMessage(String message) {
        if (contains(message, "schema", "draft schema")) return AiFailureCategory.SCHEMA_INVALID;
        if (contains(message, "selection", "recommended list", "candidate")) return AiFailureCategory.SELECTION_INVALID;
        if (contains(message, "consent", "authorization was withdrawn")) return AiFailureCategory.CONSENT_REVOKED;
        if (contains(message, "quota", "rate limit", "429")) return AiFailureCategory.QUOTA_EXHAUSTED;
        if (contains(message, "empty choices", "empty message", "empty response", "invalid response")) {
            return AiFailureCategory.PROVIDER_RESPONSE_INVALID;
        }
        if (contains(message, "timeout", "timed out", "read timed")) return AiFailureCategory.TIMEOUT;
        if (contains(message, "connection", "network", "connect")) return AiFailureCategory.CONNECTION;
        if (contains(message, "500", "502", "503", "504")) return AiFailureCategory.PROVIDER_5XX;
        if (contains(message, "400", "401", "403", "404")) return AiFailureCategory.PROVIDER_4XX;
        return AiFailureCategory.INTERNAL;
    }

    public PdfFailureCategory pdf(Throwable error) {
        if (error instanceof ResourceAccessException) {
            return contains(error.getMessage(), "timeout", "timed out", "read timed")
                    ? PdfFailureCategory.TIMEOUT : PdfFailureCategory.CONNECTION;
        }
        if (error instanceof RestClientResponseException response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == 401 || status.value() == 403) return PdfFailureCategory.AUTH;
            if (status.value() == 413) return PdfFailureCategory.INPUT_TOO_LARGE;
            return PdfFailureCategory.RENDER;
        }
        return pdfMessage(error == null ? null : error.getMessage());
    }

    public PdfFailureCategory pdfMessage(String message) {
        if (contains(message, "storage", "store")) return PdfFailureCategory.STORAGE;
        if (contains(message, "input", "too large", "413")) return PdfFailureCategory.INPUT_TOO_LARGE;
        if (contains(message, "auth", "401", "403")) return PdfFailureCategory.AUTH;
        if (contains(message, "timeout", "timed out", "read timed")) return PdfFailureCategory.TIMEOUT;
        if (contains(message, "connection", "connect", "network")) return PdfFailureCategory.CONNECTION;
        return PdfFailureCategory.RENDER;
    }

    private AiFailureCategory aiStatus(HttpStatusCode status) {
        if (status.value() == 429) return AiFailureCategory.RATE_LIMITED;
        return status.is5xxServerError() ? AiFailureCategory.PROVIDER_5XX : AiFailureCategory.PROVIDER_4XX;
    }

    private boolean contains(String value, String... candidates) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) return true;
        }
        return false;
    }
}
