package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;

import java.time.LocalDateTime;

public record ResumeVersionSummary(
        Long id,
        Integer versionNo,
        ResumeSourceType sourceType,
        String optimizationSummary,
        LocalDateTime createdAt,
        LocalDateTime archivedAt,
        Long restoredFromVersionId
) {}
