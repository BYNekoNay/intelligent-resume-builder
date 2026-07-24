package com.intelligentresume.ai.task.dto;

import com.intelligentresume.ai.task.domain.AiTaskType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 创建 AI 任务请求。
 *
 * <p>支持两种 JD 来源：
 * <ul>
 *   <li>已有 JD：传 {@code jobDescriptionId}</li>
 *   <li>内联 JD：传 {@code jdText}（可选 {@code companyName}、{@code positionTitle}）</li>
 * </ul>
 *
 * <p>{@code targetResumeId} 为可选：不传时确认后自动创建岗位简历。
 */
public record CreateAiTaskRequest(
        @NotNull AiTaskType taskType,
        Map<String, Object> input,
        Long targetResumeId,
        Long jobDescriptionId,
        String jdText,
        String companyName,
        String positionTitle,
        String resumeTitle
) {
}
