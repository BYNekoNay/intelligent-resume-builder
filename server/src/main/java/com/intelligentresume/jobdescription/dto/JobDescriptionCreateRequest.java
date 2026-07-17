package com.intelligentresume.jobdescription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JobDescriptionCreateRequest(
        @NotBlank
        @Size(max = 255)
        String title,

        @Size(max = 255)
        String companyName,

        @NotBlank
        @Size(max = 5000)
        String jdText
) {}