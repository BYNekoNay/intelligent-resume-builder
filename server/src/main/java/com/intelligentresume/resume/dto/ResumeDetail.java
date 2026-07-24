package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;

public record ResumeDetail(
        Long id,
        String title,
        Long currentVersionId,
        Long jobDescriptionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
