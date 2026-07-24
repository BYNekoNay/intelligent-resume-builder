package com.intelligentresume.ai.consent.dto;

import com.intelligentresume.ai.consent.domain.ConsentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同意状态响应。
 */
public record ConsentResponse(
        Long id,
        String policyVersion,
        String providerCode,
        List<String> taskScopes,
        List<String> dataCategories,
        ConsentStatus status,
        LocalDateTime createdAt
) {
}
