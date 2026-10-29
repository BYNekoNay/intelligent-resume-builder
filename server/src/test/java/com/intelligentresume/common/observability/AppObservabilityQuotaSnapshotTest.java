package com.intelligentresume.common.observability;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.export.repository.ExportTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额 gauge 的取值来源（2026-10-29 消除 scrape 放大）。
 *
 * <p>背景：改造前每个任务类型各自一条 {@code count(*)}，8 个类型 = 每次抓取 8 条聚合；
 * 现改为「一次分组查询 + 30s 快照」。本测试钉住两件事：
 * <ol>
 *   <li><b>一次抓取只查一次库</b>：8 个 gauge 依次回调时，分组查询只发生 1 次；</li>
 *   <li><b>数值口径不变</b>：每个类型的读数仍等于该类型当日尝试数（指标名与标签也不变）。</li>
 * </ol>
 * 只断言「指标存在」是不够的——那在「回调里恒返回 0」的变异下仍会绿。
 */
class AppObservabilityQuotaSnapshotTest {

    @Test
    @DisplayName("一次抓取只发 1 条分组查询，且各类型读数与查询结果一致")
    void oneQueryPerScrapeAndValuesMatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiTaskRepository repository = mock(AiTaskRepository.class);
        when(repository.sumAttemptsGroupedByTaskType(any(LocalDateTime.class))).thenReturn(List.of(
                new Object[]{AiTaskType.JOB_GENERATION, 7L},
                new Object[]{AiTaskType.ATS_ANALYSIS, 3L}));

        AppObservability observability = new AppObservability(registry, repository, mock(ExportTaskRepository.class));
        observability.registerQuotaLimit(AiTaskType.JOB_GENERATION, 30);
        observability.registerQuotaLimit(AiTaskType.ATS_ANALYSIS, 20);

        // 模拟一次 Prometheus 抓取：两个 gauge 各自被读一次
        double generation = registry.get("resume_ai_quota_daily_attempts")
                .tag("task_type", "JOB_GENERATION").tag("scope", "all_users").gauge().value();
        double ats = registry.get("resume_ai_quota_daily_attempts")
                .tag("task_type", "ATS_ANALYSIS").tag("scope", "all_users").gauge().value();

        assertEquals(7.0, generation, "JOB_GENERATION 当日尝试数应等于分组查询返回的值");
        assertEquals(3.0, ats, "ATS_ANALYSIS 当日尝试数应等于分组查询返回的值");
        verify(repository, times(1)).sumAttemptsGroupedByTaskType(any(LocalDateTime.class));

        // 再读一次（下一个抓取周期内）：快照未过期，不得再查库
        registry.get("resume_ai_quota_daily_attempts")
                .tag("task_type", "JOB_GENERATION").tag("scope", "all_users").gauge().value();
        verify(repository, times(1)).sumAttemptsGroupedByTaskType(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("分组查询没有该类型时读数为 0（不是 NaN）")
    void missingTypeReadsZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiTaskRepository repository = mock(AiTaskRepository.class);
        when(repository.sumAttemptsGroupedByTaskType(any(LocalDateTime.class))).thenReturn(List.of());

        AppObservability observability = new AppObservability(registry, repository, mock(ExportTaskRepository.class));
        observability.registerQuotaLimit(AiTaskType.COMMUNICATION_GENERATE, 20);

        double value = registry.get("resume_ai_quota_daily_attempts")
                .tag("task_type", "COMMUNICATION_GENERATE").tag("scope", "all_users").gauge().value();
        assertEquals(0.0, value, "无数据时必须读 0；NaN 会让告警规则的比较静默失效");
    }
}
