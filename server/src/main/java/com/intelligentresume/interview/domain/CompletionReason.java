package com.intelligentresume.interview.domain;

/**
 * 面试结束的原因，写在 {@code interview_session.completion_reason}，报告页直接展示。
 *
 * <p>要点：每种取值对应一条不同的收尾路径，新增原因必须同时补上「谁写入它」：
 * <ul>
 *   <li>{@link #AI_INFORMATION_COMPLETE}：AI 模式，已达最低题数且模型判断信息足够；</li>
 *   <li>{@link #MAX_QUESTION_LIMIT}：AI 模式，达到 {@code maxQuestionCount} 强制收尾；</li>
 *   <li>{@link #TARGET_REACHED_IN_RULE_MODE}：规则模式达到 {@code targetQuestionCount}；</li>
 *   <li>{@link #USER_FINISHED}：用户主动点结束（已至少完成 1 题）。</li>
 * </ul>
 */
public enum CompletionReason {
    AI_INFORMATION_COMPLETE,
    MAX_QUESTION_LIMIT,
    TARGET_REACHED_IN_RULE_MODE,
    USER_FINISHED
}
