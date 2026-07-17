package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ConfirmRequest(
        @NotNull
        LocalDateTime taskUpdatedAt,

        @NotEmpty
        @Size(max = 200)
        List<ConfirmedDraftItem> items,

        Map<String, Object> additionalResumeJson
) {

    public record ConfirmedDraftItem(
            @NotNull String outputPath,
            @NotNull Decision decision,
            Map<String, Object> editedValue
    ) {}

    public enum Decision { ACCEPT, EDIT, REJECT }
}