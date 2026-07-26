package com.intelligentresume.ai.selection.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public record ConfirmMaterialsRequest(
        @NotNull LocalDateTime taskUpdatedAt,
        @NotNull List<Long> selectedMaterialIds,
        List<Long> forcedIncludedMaterialIds,
        String resumeTitle
) {
}
