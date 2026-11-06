package com.intelligentresume.ats.service;

import com.intelligentresume.ats.dto.AtsFallbackCode;

/**
 * ATS AI 分析失败时抛出的专用异常：把"为什么失败"一并带出去，供上层落兜底。
 *
 * <p>一句话定位：AI 执行路上（调模型、校验返回）出任何问题，都用这个异常往外传，
 * 而不是普通异常——因为上层需要知道兜底原因码和"还能不能重试"这两个额外信息。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code fallbackCode} 会被 worker 原样写进 {@code result_json.fallback.code}；worker 还按它挑文案
 *       （INVALID_RESPONSE 与其它码的文案不同）。</li>
 *   <li>{@code retryable} 与 code 是**独立的两个维度**，最终决定任务是否还能再重试。</li>
 *   <li>只有 {@code INVALID_RESPONSE} 会在 {@code AtsAiAnalysisService} 里触发一次自动修复；
 *       而 validator 抛出的 INVALID_RESPONSE 一律 {@code retryable=false}，即"修复也救不回来就别再重试"。</li>
 * </ul>
 */
public class AtsAiAnalysisException extends RuntimeException {
    private final AtsFallbackCode fallbackCode;
    private final boolean retryable;

    public AtsAiAnalysisException(AtsFallbackCode fallbackCode, String message, boolean retryable) {
        super(message);
        this.fallbackCode = fallbackCode;
        this.retryable = retryable;
    }

    public AtsFallbackCode fallbackCode() { return fallbackCode; }
    public boolean retryable() { return retryable; }
}
