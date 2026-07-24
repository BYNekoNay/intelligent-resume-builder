package com.intelligentresume.ai.task.dto;

import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 任务状态响应。
 */
public record AiTaskStatusResponse(
        Long id,
        AiTaskType taskType,
        Long jobDescriptionId,
        AiTaskStatus status,
        Map<String, Object> resultJson,
        String errorMessage,
        ConfirmationStatus confirmationStatus,
        Long resultResumeVersionId,
        Integer retryCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
