package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeVersion;

import java.time.LocalDateTime;
import java.util.Map;

public record ResumeVersionResponse(
        Long id,
        Long resumeId,
        Integer versionNo,
        ResumeVersion.SourceType sourceType,
        Map<String, Object> resumeJson,
        String optimizationSummary,
        LocalDateTime createdAt
) {}