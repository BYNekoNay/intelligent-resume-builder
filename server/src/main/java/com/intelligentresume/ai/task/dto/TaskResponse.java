package com.intelligentresume.ai.task.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelligentresume.ai.task.domain.AiTask;

import java.time.LocalDateTime;
import java.util.Map;

public record TaskResponse(
        Long id,
        AiTask.TaskType taskType,
        AiTask.TaskStatus status,
        AiTask.ConfirmationStatus confirmationStatus,
        Map<String, Object> resultJson,
        String errorMessage,
        Integer retryCount,
        Long resultResumeVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    @JsonProperty("taskId")
    public Long taskId() {
        return id;
    }
}
