package com.intelligentresume.ai.provider;

import java.util.Map;

/**
 * AI 调用结果。
 */
public record AiCallResult(
        boolean success,
        Map<String, Object> data,
        String providerRequestId,
        boolean retryable,
        String errorMessage
) {
    public static AiCallResult ok(Map<String, Object> data, String providerRequestId) {
        return new AiCallResult(true, data, providerRequestId, false, null);
    }

    public static AiCallResult fail(String errorMessage, boolean retryable, String providerRequestId) {
        return new AiCallResult(false, null, providerRequestId, retryable, errorMessage);
    }
}
