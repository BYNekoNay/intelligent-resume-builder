package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeVersion;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record ResumeVersionCreateRequest(
        @NotNull
        Map<String, Object> resumeJson,

        @NotNull
        ResumeVersion.SourceType sourceType,

        String optimizationSummary
) {}