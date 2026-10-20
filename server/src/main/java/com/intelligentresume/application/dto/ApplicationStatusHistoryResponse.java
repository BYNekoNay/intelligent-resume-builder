package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;

public record ApplicationStatusHistoryResponse(
        Long id,
        ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        String note,
        LocalDateTime changedAt
) {}
