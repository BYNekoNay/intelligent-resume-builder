package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CareerMaterialCreateRequest(
        @NotNull
        MaterialType materialType,

        @NotBlank
        @Size(max = 255)
        String title,

        @NotNull
        Map<String, Object> contentJson,

        String sourceText,

        UsagePreference usagePreference
) {}