package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;
import java.util.Map;

public record CareerMaterialDetail(
        Long id,
        MaterialType materialType,
        String title,
        Map<String, Object> contentJson,
        String sourceText,
        UsagePreference usagePreference,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
