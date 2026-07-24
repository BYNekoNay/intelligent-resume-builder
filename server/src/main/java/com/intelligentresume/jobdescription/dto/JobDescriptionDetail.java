package com.intelligentresume.jobdescription.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record JobDescriptionDetail(
        Long id, String title, String companyName, String jdText,
        Map<String, Object> parsedKeywordsJson,
        LocalDateTime parsedAt, String parsedVersion,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {}
