package com.intelligentresume.ai.guidance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AchievementGuidanceRequest(@NotNull Long resumeVersionId,
        @NotBlank @Size(max = 64) String section, @NotBlank @Size(max = 5000) String content) {
}
