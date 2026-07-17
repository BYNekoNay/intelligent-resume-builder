package com.intelligentresume.communication.dto;

public record CommunicationGenerateResponse(CommunicationGenerateRequest.Type type, String draft,
        boolean sentAutomatically, boolean requiresManualConfirmation) {
}
