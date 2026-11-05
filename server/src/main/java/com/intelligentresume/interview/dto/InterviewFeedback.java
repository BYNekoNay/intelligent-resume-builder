package com.intelligentresume.interview.dto;

import java.util.List;

/**
 * 单轮反馈的精简视图（亮点 + 改进建议）。
 *
 * <p>要点：
 * <ul>
 *   <li>当前仓库内没有引用点，仅被 {@link AnswerInterviewResponse} 引用；实际反馈来自
 *       {@code interview_record.feedback_json}。</li>
 *   <li>真实数据的 strengths 允许为空（模型可判「无亮点」），improvements 契约上至少 1 条。</li>
 *   <li>与报告里的同名字段不同：报告会跨轮去重并截断，这里是单轮原始值。</li>
 * </ul>
 */
public record InterviewFeedback(List<String> strengths, List<String> improvements) {}
