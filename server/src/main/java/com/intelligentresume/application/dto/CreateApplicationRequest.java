package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
        @NotNull Long jobDescriptionId,
        @NotNull Long resumeVersionId,
        ApplicationStatus status,
        @Size(max = 30000) String coverLetterText,
        @Size(max = 30000) String emailBodyText,
        @Size(max = 30000) String openingMessageText
) {}
