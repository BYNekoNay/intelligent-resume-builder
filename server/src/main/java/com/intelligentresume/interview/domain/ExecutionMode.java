package com.intelligentresume.interview.domain;

/**
 * 会话的执行模式。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #AI}：每轮走模型评估；{@link #RULE}：每轮走 {@code InterviewRuleEngine} 的确定性打分。</li>
 *   <li>降级是单向的（AI → RULE），一旦切换不会回到 AI；后台 AI 结果落地时会校验仍是 AI 模式。</li>
 *   <li>{@code InterviewStatus} 与它正交：同一状态在两个模式下含义可能不同（例如 AWAITING_ANSWER）。</li>
 * </ul>
 */
public enum ExecutionMode {
    AI, RULE
}
