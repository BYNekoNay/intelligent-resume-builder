package com.intelligentresume.interview.domain;

/**
 * AI 操作类型：一场面试里只有「出首题」和「评估一次回答」两种会消耗模型的动作。
 *
 * <p>要点：
 * <ul>
 *   <li>与 {@code session_id + round_no} 组成唯一约束，因此同一轮的评估操作只能有一条 attempt。</li>
 *   <li>{@link #INITIAL_QUESTION} 创建时 {@code roundNo} 传 null（首题不属于某一轮），此时该唯一约束
 *       在 MySQL 下对 NULL 不去重，首题的重复提交只由 {@code (user_id, idempotency_key)} 拦住。</li>
 *   <li>{@link #ANSWER_EVALUATION} 必须带轮次号，规则模式的 attempt 也用它。</li>
 *   <li>重试时按此枚举决定会话回到 {@code GENERATING_QUESTION} 还是 {@code EVALUATING_ANSWER}，
 *       新增类型必须同步 {@code InterviewRetryService} 的分支。</li>
 * </ul>
 */
public enum AiAttemptOperationType {
    /** 生成首题（开场）。 */
    INITIAL_QUESTION,
    /** 评估一轮回答，并顺带产出下一题。 */
    ANSWER_EVALUATION
}
