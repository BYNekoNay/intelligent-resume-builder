package com.intelligentresume.jobdescription.dto;

import jakarta.validation.constraints.Size;

public record UpdateJobDescriptionRequest(
        @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        String jdText
) {}
