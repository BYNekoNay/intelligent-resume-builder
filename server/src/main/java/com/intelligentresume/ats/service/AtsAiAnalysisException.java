package com.intelligentresume.ats.service;

import com.intelligentresume.ats.dto.AtsFallbackCode;

public class AtsAiAnalysisException extends RuntimeException {
    private final AtsFallbackCode fallbackCode;
    private final boolean retryable;

    public AtsAiAnalysisException(AtsFallbackCode fallbackCode, String message, boolean retryable) {
        super(message);
        this.fallbackCode = fallbackCode;
        this.retryable = retryable;
    }

    public AtsFallbackCode fallbackCode() { return fallbackCode; }
    public boolean retryable() { return retryable; }
}
