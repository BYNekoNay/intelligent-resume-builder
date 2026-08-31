package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;

import java.time.LocalDateTime;
import java.util.Map;

public record ResumeVersionSummary(
        Long id,
        Integer versionNo,
        ResumeSourceType sourceType,
        String templateCode,
        String optimizationSummary,
        Map<String, Object> generationContext,
        LocalDateTime createdAt,
        LocalDateTime archivedAt,
        Long restoredFromVersionId
) {}
