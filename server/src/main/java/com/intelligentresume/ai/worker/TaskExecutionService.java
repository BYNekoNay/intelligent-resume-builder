package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.optimize.service.InlineOptimizeResultFormatter;
import com.intelligentresume.ai.selection.service.JobMaterialSelectionService;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.service.AiTaskConsentPolicy;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry;
import com.intelligentresume.ats.service.AtsAiAnalysisException;
import com.intelligentresume.ats.service.AtsAiAnalysisService;
import com.intelligentresume.ats.service.AtsResultStateService;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.observability.AiFailureCategory;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.common.observability.WorkerTraceContext;
import com.intelligentresume.communication.service.CommunicationAiException;
import com.intelligentresume.communication.service.CommunicationAiService;
import com.intelligentresume.interview.service.InterviewFollowUpAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 任务执行服务 —— 按任务能力描述符把一条已领取的任务分发到对应的业务流程,并统一收尾。
 *
 * <p>要点 / 红线:
 * <ul>
 *   <li>执行前会**再查一次**授权:任务创建到真正执行之间可能隔了很久,用户可能已撤回同意。</li>
 *   <li>分支依据是 {@code AiTaskCapabilityRegistry} 的 executionMode,不是 if-else 猜类型;
 *       新增任务类型时只改注册表,这里不需要额外分支(INTERVIEW 例外,见下)。</li>
 *   <li>INTERVIEW_COACH 走队列的只有 {@code FOLLOW_UP_PRACTICE}:未知 operation 必须显式失败,
 *       不能落到 {@code executeDefault},否则任意客户端输入都会被直接送进模型。</li>
 *   <li>无论成功、失败还是抛异常,finally 里都要取消心跳并记录指标 —— 指标漏记比少记一条日志严重得多。</li>
 *   <li>执行方法内部会自行 {@code releaseSuccess}/{@code releaseFailed};{@code execute} 本身
 *       不写任务状态,只负责调度与观测。</li>
 * </ul>
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final AiProviderRegistry providerRegistry;
    private final TaskLeaseService leaseService;
    private final JobGenerationService jobGenerationService;
    private final JobMaterialSelectionService materialSelectionService;
    private final AiConsentService consentService;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;
    private final InlineOptimizeResultFormatter inlineOptimizeResultFormatter;
    private final AtsAiAnalysisService atsAiAnalysisService;
    private final AtsResultStateService atsResultStateService;
    private final CommunicationAiService communicationAiService;
    private final InterviewFollowUpAiService interviewFollowUpAiService;
    private final AiTaskWorkerProperties workerProperties;
    /**
     * 心跳调度池：分组并发下（HEAVY+LIGHT）多个任务各有心跳，独立线程池避免
     * 单次 renew 卡顿（DB 抖动/连接池等待）连锁拖慢其它任务的续租——
     * 续租失败会使租约到期、任务被第二 worker 接管并重复调用 provider。
     */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-task-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public TaskExecutionService(AiProviderRegistry providerRegistry,
                                TaskLeaseService leaseService,
                                JobGenerationService jobGenerationService,
                                JobMaterialSelectionService materialSelectionService,
                                AiConsentService consentService,
                                AppObservability observability,
                                FailureCategoryClassifier failureCategoryClassifier,
                                InlineOptimizeResultFormatter inlineOptimizeResultFormatter,
                                AtsAiAnalysisService atsAiAnalysisService,
                                AtsResultStateService atsResultStateService,
                                CommunicationAiService communicationAiService,
                                InterviewFollowUpAiService interviewFollowUpAiService,
                                AiTaskWorkerProperties workerProperties) {
        this.providerRegistry = providerRegistry;
        this.leaseService = leaseService;
        this.jobGenerationService = jobGenerationService;
        this.materialSelectionService = materialSelectionService;
        this.consentService = consentService;
        this.observability = observability;
        this.failureCategoryClassifier = failureCategoryClassifier;
        this.inlineOptimizeResultFormatter = inlineOptimizeResultFormatter;
        this.atsAiAnalysisService = atsAiAnalysisService;
        this.atsResultStateService = atsResultStateService;
        this.communicationAiService = communicationAiService;
        this.interviewFollowUpAiService = interviewFollowUpAiService;
        this.workerProperties = workerProperties;
    }

    /**
     * 执行单个 AI 任务。
     *
     * <p>流程:取能力描述符 → 开心跳 → 校验授权 → 按 executionMode 分发 → 收尾(取消心跳、记指标)。
     *
     * <p>注意 JOB_GENERATION 只有在快照里带 {@code jobDescriptionId} 时才走领域生成流程;
     * 不带的话会落到通用 provider 分支,由 {@code PromptTemplates} 拼 prompt。
     */
    public void execute(AiTask task, String owner) {
        AiTaskCapabilityRegistry.Descriptor capability = AiTaskCapabilityRegistry.requireRegistered(task.getTaskType());
        long startedAt = System.nanoTime();
        ScheduledFuture<?> heartbeat = startHeartbeat(task.getId(), owner);
        try (WorkerTraceContext ignored = WorkerTraceContext.open(task.getId())) {
            try {
                log.debug("Executing AI task: type={}, owner={}", task.getTaskType(), owner);
                if (!hasExecutionConsent(task)) {
                    leaseService.releaseFailed(task, owner, "AI authorization was withdrawn or no longer covers this task", false);
                    if (task.getTaskType() == AiTaskType.ATS_ANALYSIS && task.getStatus() == AiTaskStatus.FAILED) {
                        markAtsFallback(task, AtsFallbackCode.CONSENT_REQUIRED, "AI 授权已撤回，已使用本地规则结果。", false, true);
                    }
                    return;
                }
                if (capability.executionMode() == AiTaskCapabilityRegistry.ExecutionMode.DOMAIN_SELECTION) {
                    executeMaterialSelection(task, owner);
                } else if (capability.executionMode() == AiTaskCapabilityRegistry.ExecutionMode.DOMAIN_GENERATION && hasJobDescriptionId(task)) {
                    executeJobGeneration(task, owner);
                } else if (capability.executionMode() == AiTaskCapabilityRegistry.ExecutionMode.ATS_ANALYSIS) {
                    executeAtsAnalysis(task, owner);
                } else if (capability.executionMode() == AiTaskCapabilityRegistry.ExecutionMode.COMMUNICATION) {
                    executeCommunicationGeneration(task, owner);
                } else if (capability.executionMode() == AiTaskCapabilityRegistry.ExecutionMode.INTERVIEW) {
                    if (isFollowUpPractice(task)) {
                        executeInterviewFollowUp(task, owner);
                    } else {
                        // #89：INTERVIEW_COACH 走 worker 队列的只有 FOLLOW_UP_PRACTICE 一种操作
                        // （面试主流程在请求内直连 provider）。未知 operation 必须显式失败，
                        // 不得静默落 executeDefault（那会把任意 input 直接送进模型）。
                        // 不回显 operation 取值，避免把客户端输入写进任务错误与日志。
                        leaseService.releaseFailed(task, owner, "Unsupported INTERVIEW_COACH operation", false);
                    }
                } else {
                    executeDefault(task, owner);
                }
            } finally {
                heartbeat.cancel(false);
                String outcome = outcome(task.getStatus());
                AiFailureCategory category = "success".equals(outcome)
                        ? AiFailureCategory.NONE : failureCategoryClassifier.aiMessage(task.getErrorMessage());
                observability.recordAiTaskAttempt(task.getTaskType(), outcome, category, task.getRetryCount(),
                        Duration.ofNanos(System.nanoTime() - startedAt));
                log.info("AI task execution completed: type={}, outcome={}, category={}, retryCount={}",
                        task.getTaskType(), outcome, category, task.getRetryCount());
            }
        }
    }

    private String outcome(AiTaskStatus status) {
        if (status == AiTaskStatus.SUCCESS) return "success";
        if (status == AiTaskStatus.PENDING) return "retry";
        return "failed";
    }

    private boolean hasExecutionConsent(AiTask task) {
        return consentService.hasValidConsent(task.getUserId(), task.getTaskType().name(),
                AiTaskConsentPolicy.requiredCategories(task.getTaskType(), task.getInputSnapshotJson()));
    }

    private boolean isFollowUpPractice(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot != null && snapshot.get("input") instanceof Map<?, ?> input) {
            return "FOLLOW_UP_PRACTICE".equals(input.get("operation"));
        }
        return "FOLLOW_UP_PRACTICE".equals(snapshot == null ? null : snapshot.get("operation"));
    }

    private void executeInterviewFollowUp(AiTask task, String owner) {
        try {
            Map<String, Object> result = interviewFollowUpAiService.executeTask(task);
            leaseService.releaseSuccess(task, owner, result);
        } catch (BusinessException e) {
            log.warn("Interview follow-up AI execution failed: errorCode={}, exception={}",
                    e.getErrorCode(), e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, e.getMessage(),
                    e.getErrorCode() == ErrorCode.AI_FAILURE || e.getErrorCode() == ErrorCode.INTERNAL);
        } catch (RuntimeException e) {
            log.warn("Unexpected interview follow-up AI execution error: exception={}",
                    e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, "Interview follow-up AI execution failed", true);
        }
    }

    private void executeMaterialSelection(AiTask task, String owner) {
        try {
            Map<String, Object> result = materialSelectionService.executeTask(task);
            task.setConfirmationStatus(ConfirmationStatus.PENDING);
            leaseService.releaseSuccess(task, owner, result);
        } catch (Exception e) {
            log.warn("Material selection execution failed: exception={}", e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, "Material selection failed: " + e.getMessage(), false);
        }
    }

    private boolean hasJobDescriptionId(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        return snapshot != null && snapshot.get("jobDescriptionId") != null;
    }

    /**
     * 岗位定制生成:校验 → 选资料 → 构建 Prompt → 调 Provider → Schema 校验 → 写结果。
     * 成功后 confirmation_status=PENDING(留给 T08 确认)。
     */
    private void executeJobGeneration(AiTask task, String owner) {
        try {
            Map<String, Object> result = jobGenerationService.executeTask(task);
            task.setConfirmationStatus(ConfirmationStatus.PENDING);
            leaseService.releaseSuccess(task, owner, result);
        } catch (Exception e) {
            log.warn("Job generation execution failed: exception={}", e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, "Generation failed: " + e.getMessage(), false);
        }
    }

    private void executeDefault(AiTask task, String owner) {
        try {
            AiCallContext ctx = new AiCallContext(
                    task.getTaskType(),
                    providerInput(task)
            );

            AiCallResult result = providerRegistry.route(task.getTaskType()).call(ctx);

            if (result.success()) {
                Map<String, Object> taskResult = task.getTaskType() == AiTaskType.INLINE_OPTIMIZE
                        ? inlineOptimizeResultFormatter.format(providerInput(task), result.data())
                        : result.data();
                leaseService.releaseSuccess(task, owner, AiTaskResults.withModelAttempts(taskResult, result));
            } else {
                leaseService.releaseFailed(task, owner, result.errorMessage(), result.retryable());
            }
        } catch (Exception e) {
            log.warn("Unexpected AI task execution error: exception={}", e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, "Internal AI task error", true);
        }
    }

    private void executeAtsAnalysis(AiTask task, String owner) {
        try {
            AtsAiAnalysisService.AnalysisResult result = atsAiAnalysisService.analyze(task);
            if (leaseService.releaseSuccess(task, owner, result.taskResult())) {
                Long resultId = atsResultId(task);
                if (resultId != null) {
                    atsResultStateService.markCompleted(resultId, task.getUserId(), task.getId(), result.insights());
                }
            }
        } catch (AtsAiAnalysisException e) {
            boolean released = leaseService.releaseFailed(task, owner, e.getMessage(), e.retryable());
            if (released && task.getStatus() == AiTaskStatus.FAILED) {
                String message = e.fallbackCode() == AtsFallbackCode.INVALID_RESPONSE
                        ? "AI 返回格式不符合要求，已使用本地规则结果。"
                        : "AI 服务暂时不可用，已使用本地规则结果。";
                markAtsFallback(task, e.fallbackCode(), message, e.retryable(), false);
            }
        } catch (RuntimeException e) {
            log.warn("Unexpected ATS AI analysis error: exception={}", e.getClass().getSimpleName());
            boolean released = leaseService.releaseFailed(task, owner, "ATS AI analysis failed", true);
            if (released && task.getStatus() == AiTaskStatus.FAILED) {
                markAtsFallback(task, AtsFallbackCode.UNKNOWN, "AI 分析未完成，已使用本地规则结果。", true, false);
            }
        }
    }

    private void executeCommunicationGeneration(AiTask task, String owner) {
        try {
            CommunicationAiService.ExecutionResult result = communicationAiService.executeTask(task);
            // #29：草稿落库在租约校验通过后、与结果写入同一事务内完成；
            // 租约被接管时结果被丢弃，草稿也不会作为孤儿留下。
            leaseService.releaseSuccess(task, owner, result.taskResult(),
                    leasedResult -> communicationAiService.persistDraft(result.pendingDraft(), leasedResult));
        } catch (CommunicationAiException e) {
            log.warn("Communication AI generation failed: retryable={}, exception={}",
                    e.retryable(), e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            log.warn("Unexpected communication AI generation error: exception={}", e.getClass().getSimpleName());
            leaseService.releaseFailed(task, owner, "Communication AI generation failed", true);
        }
    }

    private void markAtsFallback(AiTask task, AtsFallbackCode code, String message,
                                 boolean retryable, boolean consentRequired) {
        Long resultId = atsResultId(task);
        if (resultId != null) {
            atsResultStateService.markFallback(resultId, task.getUserId(), task.getId(),
                    code, message, retryable, consentRequired);
        }
    }

    private Long atsResultId(AiTask task) {
        Object value = providerInput(task).get("atsCheckResultId");
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 启动租约心跳:固定延迟续租,间隔为 {@code leaseSeconds/3},下限 1 秒。
     *
     * <p>续租失败只记日志、不在这里终止任务:租约一旦过期,任务会被别的 worker 接管并重跑,
     * 本次结果会在写终态时被租约校验丢弃。也就是说"漏续租"的代价是重复调用 provider(重复计费),
     * 调大 {@code lease-seconds} 或调小间隔时都要意识到这一点。
     */
    private ScheduledFuture<?> startHeartbeat(Long taskId, String owner) {
        long intervalSeconds = Math.max(1, workerProperties.getLeaseSeconds() / 3L);
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!leaseService.renew(taskId, owner)) {
                    log.warn("AI task {} lease is no longer owned by {}; stale completion will be discarded", taskId, owner);
                }
            } catch (RuntimeException e) {
                log.warn("Could not renew AI task {} lease for owner {}", taskId, owner, e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerInput(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot != null && snapshot.get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        return snapshot != null ? snapshot : Map.of();
    }
}
