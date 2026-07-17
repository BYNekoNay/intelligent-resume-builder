package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;

public record ResumeResponse(
        Long id,
        String title,
        Long currentVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}