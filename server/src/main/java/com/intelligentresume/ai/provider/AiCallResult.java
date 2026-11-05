package com.intelligentresume.ai.provider;

import java.util.List;
import java.util.Map;

/**
 * AI 调用结果。
 *
 * <p>{@code modelAttempts} 是 R33 的落点：模型链顺延以前只写日志，用户看不到"这次换了几个模型"。
 * 现在每次尝试都记进结果，由 worker 落到任务结果里 —— **换可以换，但不能静默**。
 *
 * <p>要点:
 * <ul>
 *   <li>成功时 {@code data} 非空、{@code errorMessage} 为 null;失败时相反。
 *       两个工厂方法({@link #ok}/{@link #fail})维持这个不变量,不要手工 new 出半成品。</li>
 *   <li>{@code retryable} 只是给调用方的提示:worker 按 R33 一律不自动重试,当前只用于日志。</li>
 *   <li>{@code errorMessage} 会进任务结果与错误展示,不得包含 prompt 或响应体原文。</li>
 * </ul>
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
