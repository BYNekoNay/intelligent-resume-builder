package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 版本详情响应：完整内容 + 元数据。
 *
 * <p>注意 {@code archivedAt} 的实际来源是实体的 {@code deletedAt}（归档＝软删时间），
 * 非归档版本该字段为 null。{@code generationContext} 只有特定来源（如 ATS 恢复）才有值。
 */
public record ResumeVersionDetail(
        Long id,
        Long resumeId,
        Integer versionNo,
        ResumeSourceType sourceType,
        Map<String, Object> resumeJson,
        String optimizationSummary,
        Map<String, Object> generationContext,
        LocalDateTime createdAt,
        LocalDateTime archivedAt,
        Long restoredFromVersionId
) {}
