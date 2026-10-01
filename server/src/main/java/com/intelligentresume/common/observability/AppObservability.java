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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;

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
    /** Micrometer Gauge 对 state 只持弱引用，此处强引用住 supplier 防止指标读数退化为 NaN。 */
    private final List<IntSupplier> gaugeStrongReferences = new CopyOnWriteArrayList<>();

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

    /**
     * 记录一次模型链内的降级：某个模型失败后顺延到下一个候选。
     *
     * <p>标签只含模型名与任务类型，不含任何业务输入或用户标识。
     */
    public void recordModelChainFallback(AiTaskType taskType, String fromModel, String toModel) {
        Counter.builder("resume_ai_model_chain_fallbacks")
                .description("Number of times the AI model chain fell back from one model to the next")
                .tags("task_type", taskType.name(), "from_model", fromModel, "to_model", toModel)
                .register(registry)
                .increment();
    }

    /**
     * 注册「模型链当前可用模型数」gauge。
     *
     * <p>由提供者在构造时调用一次；读数是惰性的，不给请求路径增加开销。
     * 该指标为 0 意味着 AI 能力实际不可用（所有模型额度耗尽或持续故障），应触发告警。
     *
     * <p><b>必须强引用住 supplier</b>：Micrometer 的 {@code Gauge} 对 state 对象只持**弱引用**，
     * 若调用方传入的是临时 lambda / 方法引用，注册后该对象即可被 GC，指标读数会变成 {@code NaN}
     * （线上实测踩到过）。这里显式持有强引用。supplier 数量与提供者数量同级，不会增长。
     */
    public void registerModelChainAvailabilityGauge(IntSupplier availableModels) {
        gaugeStrongReferences.add(availableModels);
        Gauge.builder("resume_ai_model_chain_available", availableModels, IntSupplier::getAsInt)
                .description("Number of AI model chain entries currently outside their cooldown window")
                .register(registry);
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

    /**
     * 注册每日配额观测 gauge（ideation #524）。
     *
     * <p>配额限流按「每用户当日尝试数」计（重试计次，见
     * {@code AiTaskRepository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter}），
     * 因此全站量指标必须用同一单位结算，否则「消耗量」与「限额」不可比：
     * gauge 拆成 {@code resume_ai_quota_daily_attempts{scope="all_users"}}（全站当日尝试数）
     * 与 {@code resume_ai_quota_daily_limit_per_user}（每用户限额，配置值）。Gauge 不引入
     * 用户标签，避免基数爆炸。
     */
    public void registerQuotaLimit(AiTaskType taskType, int limit) {
        if (quotaLimits.putIfAbsent(taskType, limit) != null) {
            return;
        }
        Gauge.builder("resume_ai_quota_daily_attempts", aiTaskRepository,
                        repository -> repository.countAttemptsByTaskTypeAndCreatedAtAfter(taskType, LocalDate.now().atStartOfDay()))
                .tag("task_type", taskType.name())
                .tag("scope", "all_users")
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

    /**
     * 记录一次数据生命周期分档清扫（决策 D2）。
     *
     * <p>三个阶段量共用一张图、以 {@code resource} 区分（取值是有限受管资源名，
     * 如 {@code resume_version} / {@code career_material}，不含任何业务数据或用户标识）：
     * <ul>
     *   <li>{@code retention_purge_scanned} —— 本轮取回的候选行数（超期且无引用）；</li>
     *   <li>{@code retention_purge_purged} —— 本轮实际物理删除的行数；</li>
     *   <li>{@code retention_purge_skipped} —— 扫描后变回被引用（外键冲突）而跳过的行数。</li>
     * </ul>
     * 计数为 0 时不注册该序列 —— 避免产生恒为 0 的空序列。{@code snapshotted}（被引用者转最小快照）
     * 属阶段 2，本阶段不出现。
     */
    public void recordRetentionPurge(String resource, int scanned, int purged, int skipped) {
        incrementIfPositive("retention_purge_scanned", resource, scanned);
        incrementIfPositive("retention_purge_purged", resource, purged);
        incrementIfPositive("retention_purge_skipped", resource, skipped);
    }

    private void incrementIfPositive(String metric, String resource, int amount) {
        if (amount > 0) {
            Counter.builder(metric).tag("resource", resource).register(registry).increment(amount);
        }
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
