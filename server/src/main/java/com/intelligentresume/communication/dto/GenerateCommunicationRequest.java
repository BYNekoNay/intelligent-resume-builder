package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import jakarta.validation.constraints.NotNull;
public record GenerateCommunicationRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId,
                                           @NotNull CommunicationType type,
                                           CommunicationOutputLanguage outputLanguage) {
    public CommunicationOutputLanguage normalizedLanguage() {
        return outputLanguage == null ? CommunicationOutputLanguage.ZH_CN : outputLanguage;
    }
}
