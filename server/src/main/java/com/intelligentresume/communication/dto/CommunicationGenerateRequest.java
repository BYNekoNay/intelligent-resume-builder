package com.intelligentresume.communication.dto;

import jakarta.validation.constraints.NotNull;

public record CommunicationGenerateRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId,
        @NotNull Type type) {
    public enum Type { COVER_LETTER, EMAIL, OPENING_MESSAGE }
}
