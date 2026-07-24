package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateResumeRequest(
        @NotBlank @Size(max = 255) String title,
        Map<String, Object> resumeJson
) {}
