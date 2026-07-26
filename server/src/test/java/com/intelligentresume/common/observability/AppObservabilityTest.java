package com.intelligentresume.common.observability;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.export.repository.ExportTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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
}
