package com.intelligentresume.common.observability;

import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.repository.ExportTaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Centralizes operational metrics and deliberately restricts every tag to a
 * finite set of technical values. No business payload or user identifier is
 * accepted by this class.
 */
@Component
public class AppObservability {

    private final MeterRegistry registry;
    private final AiTaskRepository aiTaskRepository;
    private final ExportTaskRepository exportTaskRepository;
    private final Map<AiTaskType, Integer> quotaLimits = new EnumMap<>(AiTaskType.class);

    public AppObservability(MeterRegistry registry,
                            AiTaskRepository aiTaskRepository,
                            ExportTaskRepository exportTaskRepository) {
        this.registry = registry;
        this.aiTaskRepository = aiTaskRepository;
        this.exportTaskRepository = exportTaskRepository;
        registerQueueGauges();
    }

    public void recordAiProviderCall(AiTaskType taskType, String provider, String model,
                                     boolean success, AiFailureCategory category, Duration duration) {
        String outcome = success ? "success" : "failure";
        Counter.builder("resume_ai_provider_calls")
                .tags("task_type", taskType.name(), "provider", provider, "model", model,
                        "outcome", outcome, "failure_category", success ? AiFailureCategory.NONE.name() : category.name())
                .register(registry)
                .increment();
        Timer.builder("resume_ai_provider_duration")
                .tags("task_type", taskType.name(), "provider", provider, "model", model, "outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void recordAiTaskAttempt(AiTaskType taskType, String outcome,
                                    AiFailureCategory category, int retryCount, Duration duration) {
        String safeCategory = "success".equals(outcome) ? AiFailureCategory.NONE.name() : category.name();
        Counter.builder("resume_ai_task_attempts")
                .tags("task_type", taskType.name(), "outcome", outcome, "failure_category", safeCategory)
                .register(registry)
                .increment();
        Timer.builder("resume_ai_task_execution_duration")
                .tags("task_type", taskType.name(), "outcome", outcome)
                .register(registry)
                .record(duration);
        if ("retry".equals(outcome)) {
            Counter.builder("resume_ai_task_retries")
                    .tag("task_type", taskType.name())
                    .register(registry)
                    .increment();
        }
        if (category == AiFailureCategory.SCHEMA_INVALID) {
            Counter.builder("resume_ai_schema_rejections")
                    .tag("task_type", taskType.name())
                    .register(registry)
                    .increment();
        }
    }

    public void recordPdfRender(String templateCode, boolean success,
                                PdfFailureCategory category, Duration duration) {
        String outcome = success ? "success" : "failure";
        Counter.builder("resume_pdf_render_calls")
                .tags("template", templateCode, "outcome", outcome,
                        "failure_category", success ? PdfFailureCategory.NONE.name() : category.name())
                .register(registry)
                .increment();
        Timer.builder("resume_pdf_render_duration")
                .tags("template", templateCode, "outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void recordPdfExport(String templateCode, boolean success,
                                PdfFailureCategory category, Duration duration) {
        String outcome = success ? "success" : "failure";
        Counter.builder("resume_pdf_export_tasks")
                .tags("template", templateCode, "outcome", outcome,
                        "failure_category", success ? PdfFailureCategory.NONE.name() : category.name())
                .register(registry)
                .increment();
        Timer.builder("resume_pdf_export_duration")
                .tags("template", templateCode, "outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void registerQuotaLimit(AiTaskType taskType, int limit) {
        if (quotaLimits.putIfAbsent(taskType, limit) != null) {
            return;
        }
        Gauge.builder("resume_ai_quota_daily_tasks_created", aiTaskRepository,
                        repository -> repository.countByTaskTypeAndCreatedAtAfter(taskType, LocalDate.now().atStartOfDay()))
                .tag("task_type", taskType.name())
                .register(registry);
        Gauge.builder("resume_ai_quota_daily_limit_per_user", quotaLimits,
                        limits -> limits.getOrDefault(taskType, 0))
                .tag("task_type", taskType.name())
                .register(registry);
    }

    public void recordQuotaRejected(AiTaskType taskType) {
        Counter.builder("resume_ai_quota_rejections")
                .tag("task_type", taskType.name())
                .register(registry)
                .increment();
    }

    private void registerQueueGauges() {
        for (AiTaskStatus status : AiTaskStatus.values()) {
            Gauge.builder("resume_ai_queue_depth", aiTaskRepository,
                            repository -> repository.countByStatus(status))
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("resume_ai_queue_oldest_pending_seconds", aiTaskRepository,
                        repository -> ageInSeconds(repository.findOldestPendingCreatedAt()))
                .register(registry);
        for (ExportStatus status : ExportStatus.values()) {
            Gauge.builder("resume_pdf_queue_depth", exportTaskRepository,
                            repository -> repository.countByStatus(status))
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("resume_pdf_queue_oldest_pending_seconds", exportTaskRepository,
                        repository -> ageInSeconds(repository.findOldestPendingCreatedAt()))
                .register(registry);
    }

    private double ageInSeconds(LocalDateTime createdAt) {
        if (createdAt == null) return 0D;
        return Math.max(0D, Duration.between(createdAt, LocalDateTime.now()).toSeconds());
    }
}
