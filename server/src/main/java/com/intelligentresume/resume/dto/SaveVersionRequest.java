package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SaveVersionRequest(
        @NotNull Map<String, Object> resumeJson,
        @NotNull ResumeSourceType sourceType,
        @Size(max = 512) String optimizationSummary
) {}
