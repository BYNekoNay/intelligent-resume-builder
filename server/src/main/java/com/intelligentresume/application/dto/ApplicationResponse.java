package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ApplicationResponse(
        Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
        String coverLetterText, String emailBodyText, String openingMessageText, String feedbackText,
        LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version, LocalDateTime createdAt, LocalDateTime updatedAt,
        List<ApplicationStatusHistoryResponse> statusHistory
) {
    /** Source compatibility for callers that construct the pre-history response in tests. */
    public ApplicationResponse(Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
                               String coverLetterText, String emailBodyText, String openingMessageText, String feedbackText,
                               LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, jobDescriptionId, resumeVersionId, status, coverLetterText, emailBodyText, openingMessageText,
                feedbackText, appliedAt, nextFollowUpAt, version, createdAt, updatedAt, List.of());
    }
}
