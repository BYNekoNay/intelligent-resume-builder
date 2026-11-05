package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;

/**
 * 一条状态迁移记录。{@code fromStatus} 可空（首条），{@code note} 为用户填写的备注，空串在落库前已被归一成 null。
 */
public record ApplicationStatusHistoryResponse(
        Long id,
        ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        String note,
        LocalDateTime changedAt
) {}
