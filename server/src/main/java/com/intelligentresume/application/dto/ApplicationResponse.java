package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;

public record ApplicationResponse(
        Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
        String coverLetterText, String emailBodyText, String openingMessageText, String feedbackText,
        LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version, LocalDateTime createdAt, LocalDateTime updatedAt
) {}
