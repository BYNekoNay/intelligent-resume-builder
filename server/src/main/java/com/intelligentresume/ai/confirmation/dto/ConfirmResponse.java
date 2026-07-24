package com.intelligentresume.ai.confirmation.dto;

import java.util.List;

/**
 * 确认响应。
 *
 * @param resumeVersionId       新创建的简历版本 ID
 * @param versionNo             版本号
 * @param resultResumeVersionId ai_task.result_resume_version_id（与 resumeVersionId 相同）
 * @param rejectedPaths         被 REJECT 的路径列表
 * @param newMaterialIds        因 EDIT 新增的 career_material ID 列表
 * @param resumeId              岗位简历 ID（新创建或已有的）
 */
public record ConfirmResponse(
        Long resumeVersionId,
        Integer versionNo,
        Long resultResumeVersionId,
        List<String> rejectedPaths,
        List<Long> newMaterialIds,
        Long resumeId
) {}
