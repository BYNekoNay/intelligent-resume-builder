package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;

import java.time.LocalDateTime;

/**
 * 版本历史摘要（ideation #50：只含元数据列）。
 *
 * <p>不包含 {@code resume_json} 与 {@code generationContext}——前者单版本上限 256KB，
 * 后者可达数 KB 且列表消费端不使用（编辑器仅从版本详情读取）。{@code templateCode}
 * 取自 V32 派生列，历史行由服务端惰性回填。
 */
public record ResumeVersionSummary(
        Long id,
        Integer versionNo,
        ResumeSourceType sourceType,
        String templateCode,
        String optimizationSummary,
        LocalDateTime createdAt,
        LocalDateTime archivedAt,
        Long restoredFromVersionId
) {}
