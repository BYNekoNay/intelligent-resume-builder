package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.util.List;

/**
 * 投递漏斗统计。
 * 分母为 0 时对应值为 null（见设计共享约定第 5 条）。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code total} 是全部投递记录数（含 DRAFT 与各种终态），也是 {@code percent} 的分母。</li>
 *     <li>{@code byStatus} 固定包含全部 9 个状态，数量为 0 的状态也会出现（不会缺行）。</li>
 *     <li>{@code conversionRates} 的三个比率分母不含草稿与终态旁支，口径见 {@code ApplicationStatsCalculator}。</li>
 *     <li>{@code avgStageDurationDays} 单位是天，保留 1 位小数；无样本时为 null 而不是 0。</li>
 * </ul>
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
