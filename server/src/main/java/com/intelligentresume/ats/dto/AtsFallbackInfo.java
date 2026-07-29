package com.intelligentresume.ats.dto;

public record AtsFallbackInfo(
        AtsFallbackCode code,
        String message,
        boolean retryable,
        boolean consentRequired
) {
}
