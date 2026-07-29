package com.intelligentresume.ats.dto;

import jakarta.validation.constraints.NotNull;

public record AtsCheckRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId, Boolean useAi) {
    public boolean shouldUseAi() {
        return useAi == null || useAi;
    }
}
