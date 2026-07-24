package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;

public record ResumeSummary(
        Long id,
        String title,
        Long currentVersionId,
        Long jobDescriptionId,
        LocalDateTime updatedAt
) {}
