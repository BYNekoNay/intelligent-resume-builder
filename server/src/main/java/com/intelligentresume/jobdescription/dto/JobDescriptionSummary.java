package com.intelligentresume.jobdescription.dto;

import java.time.LocalDateTime;

public record JobDescriptionSummary(
        Long id, String title, String companyName, LocalDateTime updatedAt
) {}
