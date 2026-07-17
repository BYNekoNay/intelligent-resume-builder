package com.intelligentresume.scoring.dto;

import jakarta.validation.constraints.NotNull;

public record MatchRequest(
        @NotNull Long resumeVersionId,
        @NotNull Long jobDescriptionId
) {}