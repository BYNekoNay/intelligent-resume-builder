package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationRecord;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplicationCreateRequest(@NotNull Long jobDescriptionId, @NotNull Long resumeVersionId,
        @NotNull ApplicationRecord.Status status, @Size(max = 20000) String coverLetterText,
        @Size(max = 20000) String emailBodyText, @Size(max = 10000) String openingMessageText, Long version) {
    public ApplicationCreateRequest(Long jobDescriptionId, Long resumeVersionId, ApplicationRecord.Status status,
                                    String coverLetterText, String emailBodyText, String openingMessageText) {
        this(jobDescriptionId, resumeVersionId, status, coverLetterText, emailBodyText, openingMessageText, null);
    }
}
