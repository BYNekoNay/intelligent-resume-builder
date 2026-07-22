package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationRecord;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplicationStatusRequest(@NotNull ApplicationRecord.Status status, @NotNull Long version,
                                       @Size(max = 20000) String feedbackText) {
}
