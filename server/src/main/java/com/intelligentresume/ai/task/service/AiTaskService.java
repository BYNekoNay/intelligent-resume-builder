package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.dto.TaskCreateRequest;
import com.intelligentresume.ai.task.dto.TaskResponse;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.repository.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 任务领域服务。
 *
 * <p>骨架:T06 落地幂等键去重 + 跨用户 + 同意校验 + 触发工作器。
 */
@Service
public class AiTaskService {

    private final AiTaskRepository repository;
    private final ConsentService consentService;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final CareerMaterialRepository materialRepository;
    private final IdempotencyService idempotencyService;

    public AiTaskService(AiTaskRepository repository,
                         ConsentService consentService,
                         ResumeRepository resumeRepository,
                         JobDescriptionRepository jobDescriptionRepository,
                         CareerMaterialRepository materialRepository,
                         IdempotencyService idempotencyService) {
        this.repository = repository;
        this.consentService = consentService;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.materialRepository = materialRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public TaskResponse create(TaskCreateRequest request, String idempotencyKey, Long userId) {
        if (!consentService.isConsented(userId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        String fingerprint = idempotencyService.fingerprint(request);
        var existing = repository.findByUserIdAndTaskTypeAndIdempotencyKey(
                userId, AiTask.TaskType.JOB_GENERATION, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Idempotency-Key is already associated with a different request");
            }
            return toResponse(existing.get());
        }

        validateOwnedReferences(request, userId);

        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(AiTask.TaskType.JOB_GENERATION);
        task.setConfirmationStatus(AiTask.ConfirmationStatus.PENDING);
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestFingerprint(fingerprint);
        task.setInputSnapshotJson(Map.of(
                "resumeId", request.targetResumeId(),
                "jobDescriptionId", request.jobDescriptionId(),
                "includedMaterialIds", request.includedMaterialIds() == null ? List.of() : request.includedMaterialIds(),
                "preferredMaterialIds", request.preferredMaterialIds() == null ? List.of() : request.preferredMaterialIds(),
                "excludedMaterialIds", request.excludedMaterialIds() == null ? List.of() : request.excludedMaterialIds(),
                "additionalInput", request.additionalInput() == null ? Map.of() : request.additionalInput()
        ));
        AiTask saved = repository.save(task);
        return toResponse(saved);
    }

    private void validateOwnedReferences(TaskCreateRequest request, Long userId) {
        resumeRepository.findByIdAndUserId(request.targetResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        jobDescriptionRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Set<Long> materialIds = new HashSet<>();
        addMaterialIds(materialIds, request.includedMaterialIds());
        addMaterialIds(materialIds, request.preferredMaterialIds());
        addMaterialIds(materialIds, request.excludedMaterialIds());
        for (Long materialId : materialIds) {
            materialRepository.findByIdAndUserId(materialId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        }
    }

    private void addMaterialIds(Set<Long> materialIds, List<Long> ids) {
        if (ids == null) return;
        for (Long id : ids) {
            if (id == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "Material ID must not be null");
            }
            materialIds.add(id);
        }
    }

    public TaskResponse get(Long id, Long userId) {
        AiTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(task);
    }

    private TaskResponse toResponse(AiTask t) {
        return new TaskResponse(t.getId(), t.getTaskType(), t.getStatus(), t.getConfirmationStatus(),
                t.getResultJson(), t.getErrorMessage(), t.getRetryCount(), t.getResultResumeVersionId(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
