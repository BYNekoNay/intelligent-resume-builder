package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResumeTitleUpdateRequest(
        @NotBlank
        @Size(max = 255)
        String title
) { }
