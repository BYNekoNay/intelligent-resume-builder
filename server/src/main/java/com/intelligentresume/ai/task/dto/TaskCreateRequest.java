package com.intelligentresume.ai.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record TaskCreateRequest(
        @NotNull
        Long targetResumeId,

        @NotNull
        Long jobDescriptionId,

        List<Long> includedMaterialIds,
        List<Long> preferredMaterialIds,
        List<Long> excludedMaterialIds,

        Map<String, Object> additionalInput
) {}