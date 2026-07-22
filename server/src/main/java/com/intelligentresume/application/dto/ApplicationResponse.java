package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationRecord;
import java.time.LocalDateTime;

public record ApplicationResponse(Long id, Long jobDescriptionId, Long resumeVersionId,
        ApplicationRecord.Status status, String coverLetterText, String emailBodyText, String openingMessageText,
        String feedbackText, LocalDateTime appliedAt, Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
