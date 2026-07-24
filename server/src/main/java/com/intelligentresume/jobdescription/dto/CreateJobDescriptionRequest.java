package com.intelligentresume.jobdescription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobDescriptionRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        @NotBlank String jdText
) {}
