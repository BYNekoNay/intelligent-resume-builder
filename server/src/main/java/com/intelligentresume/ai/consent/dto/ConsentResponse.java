package com.intelligentresume.ai.consent.dto;

import com.intelligentresume.ai.consent.domain.AiConsent;

import java.time.LocalDateTime;
import java.util.List;

public record ConsentResponse(
        Long id,
        AiConsent.ConsentEventType eventType,
        LocalDateTime createdAt,
        String policyVersion,
        String providerCode,
        List<String> taskScopes,
        List<String> dataCategories
) {}
