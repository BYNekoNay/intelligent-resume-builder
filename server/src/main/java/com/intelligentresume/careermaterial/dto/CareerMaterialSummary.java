package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;

public record CareerMaterialSummary(
        Long id,
        MaterialType materialType,
        String title,
        UsagePreference usagePreference,
        LocalDateTime updatedAt
) {}
