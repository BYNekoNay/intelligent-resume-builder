package com.intelligentresume.interview.dto;

/**
 * 单轮作答的同步响应形态：记录 id、本轮分数、反馈与下一题。
 *
 * <p>要点：
 * <ul>
 *   <li>当前仓库内没有引用点：作答接口实际返回 {@link InterviewStateResponse}（因为评估已改为异步，
 *       请求返回时还没有分数）。保留它只作结构参考，<b>不要</b>据此推断当前接口形状。</li>
 *   <li>字段语义与 {@code interview_record} + {@code feedback_json} 对应：{@code roundScore} 为五维之和（0-100），
 *       {@code nextQuestion} 是模型在同一响应里给出的下一题。</li>
 *   <li>它与 {@link InterviewFeedback} 一起构成了「同步评估」时代的返回结构，异步化后已被状态接口取代。</li>
 * </ul>
 */
public record AnswerInterviewResponse(Long recordId, String questionText, Integer roundScore,
                                      InterviewFeedback feedback, String nextQuestion) {}
