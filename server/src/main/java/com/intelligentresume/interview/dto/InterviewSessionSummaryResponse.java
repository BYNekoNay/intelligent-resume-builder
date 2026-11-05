package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSourceType;

import java.time.LocalDateTime;

/**
 * 历史面试会话摘要。actualQuestionCount / totalScore 由服务端聚合。
 *
 * <p>要点：
 * <ul>
 *   <li>只用于「已完成」会话（查询层已按 status 过滤），因此没有 status 字段。</li>
 *   <li>{@code targetQuestionCount} 是计划值，{@code actualQuestionCount} 是实际完成轮数，
 *       两者不相等是正常现象（提前收尾或达到上限）。</li>
 *   <li>{@code totalScore} 为该会话各轮平均分，不是总分。</li>
 * </ul>
 */
public record InterviewSessionSummaryResponse(
        Long id,
        Long jobDescriptionId,
        Long resumeVersionId,
        InterviewSourceType sourceType,
        InterviewMode interviewMode,
        ExecutionMode executionMode,
        CompletionReason completionReason,
        Integer targetQuestionCount,
        Integer actualQuestionCount,
        Integer totalScore,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
