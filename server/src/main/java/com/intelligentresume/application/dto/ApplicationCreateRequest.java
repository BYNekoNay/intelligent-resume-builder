package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationRecord;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplicationCreateRequest(@NotNull Long jobDescriptionId, @NotNull Long resumeVersionId,
        @NotNull ApplicationRecord.Status status, @Size(max = 20000) String coverLetterText,
        @Size(max = 10000) String openingMessageText) {
}
