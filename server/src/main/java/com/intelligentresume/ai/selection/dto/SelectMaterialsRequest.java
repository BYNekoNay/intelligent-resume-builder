package com.intelligentresume.ai.selection.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SelectMaterialsRequest(
        Long jobDescriptionId,
        String jdText,
        String companyName,
        String positionTitle,
        List<Long> includedMaterialIds,
        List<Long> preferredMaterialIds,
        List<Long> excludedMaterialIds,
        String resumeTitle
) {
}
