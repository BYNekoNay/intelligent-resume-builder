package com.intelligentresume.ai.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConsentRequest(
        @NotBlank
        @Size(max = 32)
        String policyVersion,

        @NotBlank
        @Size(max = 64)
        String providerCode,

        @NotEmpty
        List<String> taskScopes,

        @NotEmpty
        List<String> dataCategories,

        @NotBlank
        @Size(max = 128)
        String noticeHash
) {}