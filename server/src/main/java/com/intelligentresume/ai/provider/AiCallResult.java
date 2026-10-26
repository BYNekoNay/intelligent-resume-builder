package com.intelligentresume.ai.provider;

import java.util.List;
import java.util.Map;

/**
 * AI 调用结果。
 *
 * <p>{@code modelAttempts} 是 R33 的落点：模型链顺延以前只写日志，用户看不到"这次换了几个模型"。
 * 现在每次尝试都记进结果，由 worker 落到任务结果里 —— **换可以换，但不能静默**。
 */
public record AiCallResult(
        boolean success,
        Map<String, Object> data,
        String providerRequestId,
        boolean retryable,
        String errorMessage,
        List<ModelAttempt> modelAttempts
) {

    /** 单次模型尝试的结果。{@code reason} 只记可读摘要，不含 prompt 内容。 */
    public record ModelAttempt(String model, String failureCategory, String reason) {
    }

    public static AiCallResult ok(Map<String, Object> data, String providerRequestId) {
        return new AiCallResult(true, data, providerRequestId, false, null, List.of());
    }

    public static AiCallResult fail(String errorMessage, boolean retryable, String providerRequestId) {
        return new AiCallResult(false, null, providerRequestId, retryable, errorMessage, List.of());
    }

    /** 带上尝试轨迹的副本（链式调度用）。 */
    public AiCallResult withAttempts(List<ModelAttempt> attempts) {
        return new AiCallResult(success, data, providerRequestId, retryable, errorMessage, List.copyOf(attempts));
    }
}
