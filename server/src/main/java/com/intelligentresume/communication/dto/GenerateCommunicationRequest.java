package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
import jakarta.validation.constraints.NotNull;
public record GenerateCommunicationRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId, @NotNull CommunicationType type) {}
