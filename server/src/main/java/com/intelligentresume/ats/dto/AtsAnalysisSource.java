package com.intelligentresume.ats.dto;

/**
 * 结果来源：这份 {@code checks} 里的分数与结论是谁给出的。
 *
 * <ul>
 *   <li>{@link #RULES} —— 仅本地规则。{@code RULES_ONLY} / {@code RULES_FALLBACK} / {@code ANALYZING}
 *       都是它。</li>
 *   <li>{@link #HYBRID} —— 规则分 + AI 结论，只在 {@code analysisStatus=COMPLETED} 时出现。</li>
 * </ul>
 *
 * <p>{@code aiInsights} 为 null 不代表没有来源标记（ANALYZING 时它也是 null），
 * 判断"AI 那部分到哪一步了"请用 {@code analysisStatus}。
 */
public enum AtsAnalysisSource {
    RULES,
    HYBRID
}
