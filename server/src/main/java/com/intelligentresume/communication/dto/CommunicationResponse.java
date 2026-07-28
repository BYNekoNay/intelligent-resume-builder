package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
public record CommunicationResponse(CommunicationType type, String draft, boolean sentAutomatically, boolean requiresManualConfirmation) {}
