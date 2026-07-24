package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 任务执行服务。按 taskType 分发:JOB_GENERATION(含 jobDescriptionId)
 * 走岗位定制生成流程;其余走通用 Provider 调用。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final AiProviderRegistry providerRegistry;
    private final TaskLeaseService leaseService;
    private final JobGenerationService jobGenerationService;

    public TaskExecutionService(AiProviderRegistry providerRegistry,
                                TaskLeaseService leaseService,
                                JobGenerationService jobGenerationService) {
        this.providerRegistry = providerRegistry;
        this.leaseService = leaseService;
        this.jobGenerationService = jobGenerationService;
    }

    /**
     * 执行单个 AI 任务。
     */
    public void execute(AiTask task, String owner) {
        log.debug("Executing task {} (type={}, owner={})", task.getId(), task.getTaskType(), owner);
        if (task.getTaskType() == AiTaskType.JOB_GENERATION && hasJobDescriptionId(task)) {
            executeJobGeneration(task);
        } else {
            executeDefault(task);
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
    private void executeJobGeneration(AiTask task) {
        try {
            Map<String, Object> result = jobGenerationService.executeTask(task);
            task.setConfirmationStatus(ConfirmationStatus.PENDING);
            leaseService.releaseSuccess(task, result);
        } catch (Exception e) {
            log.error("Job generation failed for task {}", task.getId(), e);
            leaseService.releaseFailed(task, "Generation failed: " + e.getMessage(), false);
        }
    }

    private void executeDefault(AiTask task) {
        try {
            AiCallContext ctx = new AiCallContext(
                    task.getTaskType(),
                    providerInput(task),
                    DEFAULT_TIMEOUT_MS
            );

            AiCallResult result = providerRegistry.route(task.getTaskType()).call(ctx);

            if (result.success()) {
                leaseService.releaseSuccess(task, result.data());
            } else {
                leaseService.releaseFailed(task, result.errorMessage(), result.retryable());
            }
        } catch (Exception e) {
            log.error("Unexpected error executing task {}", task.getId(), e);
            leaseService.releaseFailed(task, "Internal error: " + e.getMessage(), true);
        }
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
