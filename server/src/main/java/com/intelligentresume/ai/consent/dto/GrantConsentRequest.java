package com.intelligentresume.ai.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 授权 AI 数据处理请求。
 */
public record GrantConsentRequest(
        @NotBlank String policyVersion,
        @NotBlank String providerCode,
        @NotNull List<String> taskScopes,
        @NotNull List<String> dataCategories,
        @NotBlank String noticeHash
) {
}
