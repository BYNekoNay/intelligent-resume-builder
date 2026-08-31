package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateApplicationStatusRequest(
        @NotNull ApplicationStatus status,
        @NotNull Long version,
        @Size(max = 30000) String feedbackText
) {}
