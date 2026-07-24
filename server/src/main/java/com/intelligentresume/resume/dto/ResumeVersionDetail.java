package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;

import java.time.LocalDateTime;
import java.util.Map;

public record ResumeVersionDetail(
        Long id,
        Integer versionNo,
        ResumeSourceType sourceType,
        Map<String, Object> resumeJson,
        String optimizationSummary,
        Map<String, Object> generationContext,
        LocalDateTime createdAt,
        LocalDateTime archivedAt,
        Long restoredFromVersionId
) {}
