package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateCareerMaterialRequest(
        @NotNull MaterialType materialType,
        @NotBlank @Size(max = 255) String title,
        @NotNull Map<String, Object> contentJson,
        @Size(max = 65535) String sourceText,
        UsagePreference usagePreference
) {}
