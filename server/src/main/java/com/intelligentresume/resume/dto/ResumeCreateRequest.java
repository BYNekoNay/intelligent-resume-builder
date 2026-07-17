package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record ResumeCreateRequest(
        @NotBlank
        @Size(max = 255)
        String title,

        @NotNull
        Map<String, Object> resumeJson
) {}