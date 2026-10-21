package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.dto.ApplicationStatsResponse;
import com.intelligentresume.application.repository.ApplicationRecordRepository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 投递统计的**口径与算法**（从 {@code ApplicationService} 搬出，纯函数、无 Spring 依赖）。
 *
 * <p>拆出来的理由只有一个：`ApplicationService` 单文件已超 300 行硬上限，统计算法是
 * 职责最内聚的一块。搬出来之后它成为纯函数（入参是纯数据、无 repository、无事务），
 * 统计口径可以被单元测试直接钉住，不必再起 Spring 容器。
 *
 * <p>**行为零变化**：算法、取整、空值语义与原实现逐字一致，{@code ApplicationService#stats}
 * 仍然负责两次仓储调用与事务边界，只把计算委托到这里。
 */
public final class ApplicationStatsCalculator {

    private ApplicationStatsCalculator() {
    }

    /**
     * 汇总一次统计结果。
     *
     * @param statusCounts SQL 聚合出的「状态 → 数量」行
     * @param durationRows 需要逐行时间戳的明细行（只服务三个阶段时长）
     * @param now          统一的时间基准（原实现各阶段各自取 now，这里收敛成一个，避免同一次
     *                     统计里三个时长用了不同时刻）
     */
    public static ApplicationStatsResponse calculate(
            List<ApplicationRecordRepository.StatusCountProjection> statusCounts,
            List<ApplicationRecordRepository.StatsProjection> durationRows,
            LocalDateTime now) {
        // #3：计数在 SQL 聚合（group by），只有需要逐行时间戳的三个阶段才读行；
        // 时长聚合在 Java 侧计算，兼容 MySQL 与 H2（不依赖 TIMESTAMPDIFF）。
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status, 0L);
        }
        long total = 0L;
        for (ApplicationRecordRepository.StatusCountProjection row : statusCounts) {
            counts.put(row.getStatus(), row.getCount());
            total += row.getCount();
        }

        // byStatus：各状态数量 + count/total*100（保留 1 位小数）
        List<ApplicationStatsResponse.StatusCount> byStatus = new ArrayList<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            long count = counts.get(status);
            Double percent = total == 0 ? null : Math.round(count * 1000.0 / total) / 10.0;
            byStatus.add(new ApplicationStatsResponse.StatusCount(status, count, percent));
        }

        // 转化率：排除 REJECTED/WITHDRAWN 对分母的干扰
        long appliedOrFurther = countIn(counts, ApplicationStatus.APPLIED, ApplicationStatus.SCREENING,
                ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.HIRED);
        long interviewingOrFurther = countIn(counts, ApplicationStatus.SCREENING,
                ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.HIRED);
        long offered = countIn(counts, ApplicationStatus.OFFERED, ApplicationStatus.HIRED);
        Double appliedToInterviewing = ratio(interviewingOrFurther, appliedOrFurther);
        Double interviewingToOffered = ratio(offered, interviewingOrFurther);
        Double appliedToOffered = ratio(offered, appliedOrFurther);

        // 平均停留（近似）：分母 0 → null
        Double appliedDuration = avgAppliedDuration(durationRows, now);
        Double interviewingDuration = avgInterviewingDuration(durationRows, now);
        Double totalToOffer = avgTotalToOffer(durationRows);

        return new ApplicationStatsResponse((int) total, byStatus,
                new ApplicationStatsResponse.ConversionRates(appliedToInterviewing, interviewingToOffered, appliedToOffered),
                new ApplicationStatsResponse.StageDurations(appliedDuration, interviewingDuration, totalToOffer));
    }

    private static long countIn(Map<ApplicationStatus, Long> counts, ApplicationStatus... statuses) {
        long sum = 0L;
        for (ApplicationStatus status : statuses) {
            sum += counts.get(status);
        }
        return sum;
    }

    private static Double ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return Math.round(numerator * 1000.0 / denominator) / 1000.0;
    }

    private static Double avgAppliedDuration(List<ApplicationRecordRepository.StatsProjection> records,
                                             LocalDateTime now) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.APPLIED || record.getStatus() == ApplicationStatus.SCREENING)
                .map(record -> {
                    LocalDateTime base = record.getAppliedAt() != null ? record.getAppliedAt() : record.getCreatedAt();
                    return base == null ? null : ChronoUnit.MINUTES.between(base, now) / 1440.0;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }

    private static Double avgInterviewingDuration(List<ApplicationRecordRepository.StatsProjection> records,
                                                  LocalDateTime now) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.INTERVIEWING)
                .map(record -> {
                    // 修复 P1-4（模块核实报告）：进入面试时刻改用 stageEnteredAt
                    // （状态迁移时写入），不再用 updatedAt（任何字段更新都会刷新它，
                    // 导致停留时长被静默重置）。stageEnteredAt 为 NULL 的历史行
                    // （V26 回填未覆盖的极端情况）回退 updatedAt 近似。
                    LocalDateTime base = record.getStageEnteredAt() != null
                            ? record.getStageEnteredAt() : record.getUpdatedAt();
                    return base == null ? null : ChronoUnit.MINUTES.between(base, now) / 1440.0;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }

    private static Double avgTotalToOffer(List<ApplicationRecordRepository.StatsProjection> records) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.OFFERED)
                .map(record -> {
                    LocalDateTime base = record.getAppliedAt() != null ? record.getAppliedAt() : record.getCreatedAt();
                    LocalDateTime offeredAt = record.getStageEnteredAt() != null
                            ? record.getStageEnteredAt() : record.getUpdatedAt();
                    if (base == null || offeredAt == null) return null;
                    return ChronoUnit.MINUTES.between(base, offeredAt) / 1440.0;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }
}
