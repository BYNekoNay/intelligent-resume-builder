package com.intelligentresume.ai.task.dto;

import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;

import java.time.LocalDateTime;

/**
 * 续办列表元数据（ideation #52）。
 *
 * <p>首页「继续处理」入口只需要 id / 类型 / 状态 / 时间；生成结果 JSON
 * （单任务可达 64KB）与输入快照不进列表响应。需要完整结果时走
 * {@code GET /api/ai/tasks/{id}}。
 *
 * <p>契约变更（2026-09-30）：不再返回 {@code resultJson}、{@code errorMessage}、
 * {@code jobDescriptionId}；Web 侧仅 HomeView 消费该列表，未使用以上字段。
 */
public record AiTaskContinuationResponse(
        Long id,
        AiTaskType taskType,
        Long parentTaskId,
        AiTaskStatus status,
        ConfirmationStatus confirmationStatus,
        Long resultResumeVersionId,
        Integer retryCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}