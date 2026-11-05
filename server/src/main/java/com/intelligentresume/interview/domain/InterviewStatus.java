package com.intelligentresume.interview.domain;

/**
 * 会话状态机。所有流程都以它做准入判断，改动前先确认每个状态的出边。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #GENERATING_QUESTION} / {@link #EVALUATING_ANSWER} 是「AI 正在跑」的两个中间态，
 *       此时不允许作答、结束或降级；前端在这两个状态下轮询 {@code GET /interviews/{id}}。</li>
 *   <li>{@link #AWAITING_ANSWER} 是唯一接受作答的状态。</li>
 *   <li>{@link #AI_ACTION_REQUIRED} 表示 AI 失败、等待用户选择「重试」或「转规则模式」；
 *       重试与降级都以它为前提。</li>
 *   <li>{@link #COMPLETED} 是终态，只能读报告；重复结束请求按幂等处理直接返回。</li>
 *   <li>后台 AI 结果落地前必须复核状态，否则会覆盖期间发生的状态变更。</li>
 * </ul>
 */
public enum InterviewStatus {
    GENERATING_QUESTION,
    AWAITING_ANSWER,
    EVALUATING_ANSWER,
    AI_ACTION_REQUIRED,
    COMPLETED
}
