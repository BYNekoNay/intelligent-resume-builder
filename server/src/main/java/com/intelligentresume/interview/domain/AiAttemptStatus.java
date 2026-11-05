package com.intelligentresume.interview.domain;

/**
 * 一次 AI attempt 的生命周期状态。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #PROCESSING} 是唯一的「进行中」状态，也是陈旧接管的判定对象；
 *       只有它允许被后台结果写回。</li>
 *   <li>{@link #FAILED} 必须带 errorCode 与 retryable，前端据此决定是否显示重试。</li>
 *   <li>{@link #RULE_FALLBACK} 表示该轮已由规则评分接管（不调用模型），语义上等同「有结论」，
 *       幂等回放时按成功处理。</li>
 *   <li>状态只能沿 {@code PROCESSING → SUCCESS/FAILED/RULE_FALLBACK} 走，落地前必须重新校验状态。</li>
 * </ul>
 */
public enum AiAttemptStatus {
    PROCESSING, SUCCESS, FAILED, RULE_FALLBACK
}
