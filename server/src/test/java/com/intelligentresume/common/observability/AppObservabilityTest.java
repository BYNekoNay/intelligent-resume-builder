package com.intelligentresume.common.observability;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.export.repository.ExportTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppObservabilityTest {

    @Test
    void recordsRetrySchemaRejectionAndQuotaRejectionWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppObservability observability = new AppObservability(registry, mock(AiTaskRepository.class),
                mock(ExportTaskRepository.class));

        observability.recordAiTaskAttempt(AiTaskType.JOB_GENERATION, "retry",
                AiFailureCategory.SCHEMA_INVALID, 1, Duration.ofMillis(25));
        observability.recordQuotaRejected(AiTaskType.JOB_GENERATION);

        assertEquals(1D, registry.get("resume_ai_task_retries")
                .tag("task_type", "JOB_GENERATION").counter().count());
        assertEquals(1D, registry.get("resume_ai_schema_rejections")
                .tag("task_type", "JOB_GENERATION").counter().count());
        assertEquals(1D, registry.get("resume_ai_quota_rejections")
                .tag("task_type", "JOB_GENERATION").counter().count());
    }

    @Test
    @DisplayName("#524 配额观测口径：全站 gauge 用与限流一致的「尝试数」，不按任务行数")
    void registersQuotaGaugesWithAttemptSemantics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiTaskRepository repository = mock(AiTaskRepository.class);
        when(repository.countAttemptsByTaskTypeAndCreatedAtAfter(eq(AiTaskType.JOB_GENERATION), any(LocalDateTime.class)))
                .thenReturn(3L);
        AppObservability observability = new AppObservability(registry, repository, mock(ExportTaskRepository.class));

        observability.registerQuotaLimit(AiTaskType.JOB_GENERATION, 20);

        assertEquals(3D, registry.get("resume_ai_quota_daily_attempts")
                .tag("task_type", "JOB_GENERATION").tag("scope", "all_users").gauge().value());
        assertNull(registry.find("resume_ai_quota_daily_tasks_created").gauge(),
                "旧「任务行数」口径指标不再注册（与限流单位不可比）");
        assertEquals(20D, registry.get("resume_ai_quota_daily_limit_per_user")
                .tag("task_type", "JOB_GENERATION").gauge().value());

        // 重复注册同一任务类型幂等（不叠加 gauge）
        observability.registerQuotaLimit(AiTaskType.JOB_GENERATION, 99);
        assertEquals(20D, registry.get("resume_ai_quota_daily_limit_per_user")
                .tag("task_type", "JOB_GENERATION").gauge().value());
    }
}
