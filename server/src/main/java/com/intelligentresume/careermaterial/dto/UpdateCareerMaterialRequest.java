package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.UsagePreference;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateCareerMaterialRequest(
        @Size(max = 255) String title,
        Map<String, Object> contentJson,
        @Size(max = 65535) String sourceText,
        UsagePreference usagePreference
) {}
