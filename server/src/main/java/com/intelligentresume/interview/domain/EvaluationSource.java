package com.intelligentresume.interview.domain;

/**
 * 单轮评估的来源。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #AI} / {@link #RULE} 会落到 {@code interview_record.evaluation_source}；</li>
 *   <li>{@link #MIXED} 只出现在报告聚合结果里（同一场既有 AI 轮次又有规则轮次），
 *       不会写入单条记录；</li>
 *   <li>前端的「AI 评估/规则评估」提示依赖它，丢弃该字段会让用户误信规则分。</li>
 * </ul>
 */
public enum EvaluationSource {
    AI, RULE, MIXED
}
