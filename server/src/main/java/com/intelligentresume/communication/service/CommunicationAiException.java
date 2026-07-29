package com.intelligentresume.communication.service;

public class CommunicationAiException extends RuntimeException {
    private final boolean retryable;

    public CommunicationAiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
