package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.InterviewStatus;

/**
 * 开场响应的精简视图（会话 id + 首题 + 状态）。
 *
 * <p>要点：
 * <ul>
 *   <li>当前仓库内没有引用点：开场接口实际返回 {@link InterviewStateResponse}，
 *       里面包含这里没有的题数区间、aiFailure 等字段。</li>
 *   <li>由于没有引用点，null 约束无从体现；真正下发的状态结构请以 {@link InterviewStateResponse} 为准。</li>
 *   <li>三个字段是状态响应中 interviewId / currentQuestion / status 的子集。</li>
 * </ul>
 */
public record StartInterviewResponse(Long interviewId, String firstQuestion, InterviewStatus status) {}
