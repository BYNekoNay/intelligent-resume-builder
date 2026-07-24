package com.intelligentresume.scoring.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 评分请求。
 */
public record MatchRequest(
        @NotNull Long resumeVersionId,
        @NotNull Long jobDescriptionId
) {}
