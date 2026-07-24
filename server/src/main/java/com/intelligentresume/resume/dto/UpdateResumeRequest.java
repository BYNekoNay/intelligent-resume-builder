package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.Size;

public record UpdateResumeRequest(
        @Size(max = 255) String title
) {}
