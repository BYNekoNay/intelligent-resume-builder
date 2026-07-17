package com.intelligentresume.ats.dto;

import jakarta.validation.constraints.NotNull;

public record AtsCheckRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId) {
}
