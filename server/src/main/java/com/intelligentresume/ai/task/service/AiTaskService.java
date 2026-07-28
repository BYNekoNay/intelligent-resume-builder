package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.worker.AiTaskWorkerProperties;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/**
 * AI 任务服务。处理任务创建(含幂等性检查)和查询。
 */
@Service
public class AiTaskService {

    private final AiTaskRepository taskRepository;
    private final AiConsentService consentService;
    private final AiQuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final AiTaskWorkerProperties workerProperties;

    public AiTaskService(AiTaskRepository taskRepository,
                         AiConsentService consentService,
                         AiQuotaService quotaService,
                         IdempotencyService idempotencyService,
                         AiTaskWorkerProperties workerProperties) {
        this.taskRepository = taskRepository;
        this.consentService = consentService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.workerProperties = workerProperties;
    }

    /**
     * 创建 AI 任务。
     *
     * <ol>
     *   <li>校验 AI 同意</li>
     *   <li>校验配额</li>
     *   <li>计算请求指纹</li>
     *   <li>幂等性检查:相同 idempotencyKey + 相同指纹 → 返回已有任务;不同指纹 → CONFLICT</li>
     *   <li>创建 PENDING 任务</li>
     * </ol>
     */
    @Transactional
    public AiTaskStatusResponse create(CreateAiTaskRequest req, String idempotencyKey, Long userId) {
        // 1. 校验同意
        if (!consentService.hasValidConsent(userId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        List<String> requiredCategories = requiredCategories(req.taskType());
        if (!consentService.hasValidConsent(userId, req.taskType().name(), requiredCategories)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED,
                    "AI authorization does not cover this task or its data categories");
        }

        // 2. 校验配额
        // 3. 计算指纹
        Map<String, Object> inputSnapshot = buildInputSnapshot(req);
        String fingerprint = idempotencyService.fingerprint(inputSnapshot);

        // 4. 幂等性检查
        Optional<AiTask> existing = taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                userId, req.taskType(), idempotencyKey);
        if (existing.isPresent()) {
            AiTask task = existing.get();
            if (task.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(task);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "相同幂等键的请求内容不一致");
        }

        quotaService.check(userId, req.taskType());

        // 5. 创建任务
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(req.taskType());
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestFingerprint(fingerprint);
        task.setInputSnapshotJson(inputSnapshot);
        task.setStatus(AiTaskStatus.PENDING);
        task.setRetryCount(0);

        task = taskRepository.save(task);
        return toResponse(task);
    }

    /**
     * 查询任务状态。
     */
    @Transactional(readOnly = true)
    public AiTaskStatusResponse get(Long id, Long userId) {
        AiTask task = taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 任务不存在"));
        return toResponse(task);
    }

    private Map<String, Object> buildInputSnapshot(CreateAiTaskRequest req) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("taskType", req.taskType().name());
        if (req.input() != null) {
            snapshot.put("input", req.input());
        }
        if (req.targetResumeId() != null) {
            snapshot.put("targetResumeId", req.targetResumeId());
        }
        if (req.jobDescriptionId() != null) {
            snapshot.put("jobDescriptionId", req.jobDescriptionId());
        }
        if (req.jdText() != null && !req.jdText().isBlank()) {
            snapshot.put("jdText", req.jdText());
        }
        if (req.companyName() != null && !req.companyName().isBlank()) {
            snapshot.put("companyName", req.companyName());
        }
        if (req.positionTitle() != null && !req.positionTitle().isBlank()) {
            snapshot.put("positionTitle", req.positionTitle());
        }
        if (req.resumeTitle() != null && !req.resumeTitle().isBlank()) {
            snapshot.put("resumeTitle", req.resumeTitle());
        }
        return snapshot;
    }

    public AiTaskStatusResponse toResponse(AiTask task) {
        return new AiTaskStatusResponse(
                task.getId(),
                task.getTaskType(),
                task.getParentTaskId(),
                toLong(task.getInputSnapshotJson().get("jobDescriptionId")),
                task.getStatus(),
                task.getResultJson(),
                task.getErrorMessage(),
                task.getConfirmationStatus(),
                task.getResultResumeVersionId(),
                task.getRetryCount(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // A malformed legacy snapshot should not make task status unreadable.
            }
        }
        return null;
    }

    @Transactional
    public AiTaskStatusResponse retry(Long taskId, Long userId) {
        AiTask task = taskRepository.findByIdAndUserId(taskId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 任务不存在"));
        if (!com.intelligentresume.ai.task.domain.AiTaskStatus.FAILED.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有失败的任务可以重试");
        }
        if (task.getRetryCount() >= workerProperties.getMaxRetries()) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 任务已达到最大重试次数");
        }
        if (!consentService.hasValidConsent(userId)
                || !consentService.hasValidConsent(userId, task.getTaskType().name(), requiredCategories(task.getTaskType()))) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        quotaService.check(userId, task.getTaskType());
        task.setStatus(com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING);
        task.setErrorMessage(null);
        taskRepository.save(task);
        return toResponse(task);
    }

    private List<String> requiredCategories(com.intelligentresume.ai.task.domain.AiTaskType type) {
        return switch (type) {
            case JOB_MATERIAL_SELECTION, JOB_GENERATION ->
                    List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE");
            default -> List.of();
        };
    }
}
