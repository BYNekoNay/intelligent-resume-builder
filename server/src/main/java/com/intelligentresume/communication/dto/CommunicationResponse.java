package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.CommunicationGenerationSource;

public record CommunicationResponse(CommunicationType type, String draft, boolean sentAutomatically,
                                    boolean requiresManualConfirmation,
                                    CommunicationGenerationSource generationSource) {}
