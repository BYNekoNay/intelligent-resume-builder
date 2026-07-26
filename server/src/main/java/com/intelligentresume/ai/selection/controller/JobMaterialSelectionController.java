package com.intelligentresume.ai.selection.controller;

import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.selection.dto.SelectMaterialsRequest;
import com.intelligentresume.ai.selection.service.MaterialSelectionConfirmationService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class JobMaterialSelectionController {

    private final AiTaskService taskService;
    private final MaterialSelectionConfirmationService confirmationService;
    private final JobDescriptionRepository jobRepository;
    private final JobGenerationService generationService;
    private final AiTaskRepository taskRepository;

    public JobMaterialSelectionController(AiTaskService taskService,
                                          MaterialSelectionConfirmationService confirmationService,
                                          JobDescriptionRepository jobRepository,
                                          JobGenerationService generationService,
                                          AiTaskRepository taskRepository) {
        this.taskService = taskService;
        this.confirmationService = confirmationService;
        this.jobRepository = jobRepository;
        this.generationService = generationService;
        this.taskRepository = taskRepository;
    }

    @PostMapping("/select-materials-for-job")
    @Transactional
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> select(
            @Valid @RequestBody SelectMaterialsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        Long userId = currentUserId(servletRequest);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
        Long jobId = request.jobDescriptionId();
        if (jobId == null && request.jdText() != null && !request.jdText().isBlank()) {
            AiTask existing = taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                    userId, AiTaskType.JOB_MATERIAL_SELECTION, key).orElse(null);
            if (existing != null) {
                if (matchesPastedRequest(existing, request, userId)) {
                    return ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(ApiResponse.success(taskService.toResponse(existing), traceId(servletRequest)));
                }
                throw new BusinessException(ErrorCode.CONFLICT, "Idempotency key was already used for a different request");
            }
            JobDescription job = new JobDescription();
            job.setUserId(userId);
            job.setTitle(request.positionTitle() == null || request.positionTitle().isBlank()
                    ? "Temporary job" : request.positionTitle());
            job.setCompanyName(request.companyName());
            job.setJdText(request.jdText());
            jobId = jobRepository.save(job).getId();
        }
        if (jobId == null) throw new BusinessException(ErrorCode.VALIDATION, "A job description is required");
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        generationService.validateMaterialIds(userId, request.includedMaterialIds(),
                request.preferredMaterialIds(), request.excludedMaterialIds());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("includedMaterialIds", safe(request.includedMaterialIds()));
        input.put("preferredMaterialIds", safe(request.preferredMaterialIds()));
        input.put("excludedMaterialIds", safe(request.excludedMaterialIds()));
        CreateAiTaskRequest taskRequest = new CreateAiTaskRequest(AiTaskType.JOB_MATERIAL_SELECTION,
                input, null, jobId, null, null, null, request.resumeTitle());
        AiTaskStatusResponse task = taskService.create(taskRequest,
                key,
                userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(servletRequest)));
    }

    @PostMapping("/tasks/{taskId}/confirm-materials")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> confirm(
            @PathVariable Long taskId,
            @Valid @RequestBody ConfirmMaterialsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        AiTask task = confirmationService.confirm(taskId, request, idempotencyKey, currentUserId(servletRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(taskService.toResponse(task), traceId(servletRequest)));
    }

    private Object safe(Object value) { return value == null ? java.util.List.of() : value; }

    @SuppressWarnings("unchecked")
    private boolean matchesPastedRequest(AiTask task, SelectMaterialsRequest request, Long userId) {
        Object jobId = task.getInputSnapshotJson().get("jobDescriptionId");
        if (!(jobId instanceof Number number)) return false;
        JobDescription job = jobRepository.findByIdAndUserId(number.longValue(), userId).orElse(null);
        if (job == null || !request.jdText().equals(job.getJdText())
                || !java.util.Objects.equals(blankToDefault(request.positionTitle()), job.getTitle())
                || !java.util.Objects.equals(request.companyName(), job.getCompanyName())) return false;
        Object rawInput = task.getInputSnapshotJson().get("input");
        if (!(rawInput instanceof Map<?, ?> input)) return false;
        return java.util.Objects.equals(safe(request.includedMaterialIds()), input.get("includedMaterialIds"))
                && java.util.Objects.equals(safe(request.preferredMaterialIds()), input.get("preferredMaterialIds"))
                && java.util.Objects.equals(safe(request.excludedMaterialIds()), input.get("excludedMaterialIds"));
    }

    private String blankToDefault(String value) { return value == null || value.isBlank() ? "Temporary job" : value; }
    private Long currentUserId(HttpServletRequest request) {
        Object id = request.getAttribute("currentUserId");
        if (id == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) id;
    }
    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
