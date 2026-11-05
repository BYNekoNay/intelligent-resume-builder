package com.intelligentresume.ai.task.dto;

import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 任务状态响应(单任务详情)。
 *
 * <p>要点:
 * <ul>
 *   <li>{@code errorMessage} 是类别化后的公开文案,不是库里的 provider 原文。</li>
 *   <li>{@code confirmationStatus} 已归一到 NOT_REQUIRED,前端不会收到 null。</li>
 *   <li>{@code resultJson} 在保留期清理后可能为空(快照与结果被压缩成元数据)。</li>
 *   <li>列表场景不要用这个 DTO:它带整份结果 JSON(单任务可达几十 KB),列表走
 *       {@link AiTaskContinuationResponse}。</li>
 * </ul>
 */
public record AiTaskStatusResponse(
        Long id,
        AiTaskType taskType,
        Long parentTaskId,
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
