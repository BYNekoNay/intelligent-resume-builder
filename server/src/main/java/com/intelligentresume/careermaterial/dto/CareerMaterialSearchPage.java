package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;

import java.util.List;
import java.util.Map;

public record CareerMaterialSearchPage(
        List<CareerMaterialSearchItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<MaterialType, Long> typeCounts
) {}
