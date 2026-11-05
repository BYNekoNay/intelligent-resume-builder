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
 * <p>{@code targetResumeId} 为可选:不传时确认后自动创建岗位简历。
 *
 * <p>要点:
 * <ul>
 *   <li>{@code input} 是自由格式 map,会被整体写入任务快照、参与幂等指纹,并作为 prompt 数据来源;
 *       以 {@code _} 开头的键被视为内部开关,不会进入 prompt 正文。</li>
 *   <li>{@code input} 的序列化体积受 {@code app.ai.max-input-json-bytes} 限制。</li>
 *   <li>JD 两种来源互斥使用:传了 {@code jobDescriptionId} 就按已有 JD 处理,
 *       传 {@code jdText} 则按内联 JD 处理,后者可附带公司名与岗位名用于简历命名。</li>
 * </ul>
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
