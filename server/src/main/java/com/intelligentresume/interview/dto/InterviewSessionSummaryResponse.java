package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSourceType;

import java.time.LocalDateTime;

/**
 * 历史面试会话摘要。actualQuestionCount / totalScore 由服务端聚合。
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
