package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.util.List;

/**
 * 投递漏斗统计。
 * 分母为 0 时对应值为 null（见设计共享约定第 5 条）。
 */
public record ApplicationStatsResponse(
        int total,
        List<StatusCount> byStatus,
        ConversionRates conversionRates,
        StageDurations avgStageDurationDays
) {
    public record StatusCount(ApplicationStatus status, long count, Double percent) {}

    public record ConversionRates(Double appliedToInterviewing, Double interviewingToOffered, Double appliedToOffered) {}

    public record StageDurations(Double applied, Double interviewing, Double totalToOffer) {}
}
