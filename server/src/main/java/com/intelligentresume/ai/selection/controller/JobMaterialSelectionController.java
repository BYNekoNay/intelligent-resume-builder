package com.intelligentresume.ai.selection.controller;

import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.selection.dto.SelectMaterialsRequest;
import com.intelligentresume.ai.selection.service.MaterialSelectionConfirmationService;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.selection.service.MaterialSelectionContextBuilder;
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
    private final MaterialSelectionContextBuilder contextBuilder;
    private final AiPreviewService previewService;
    private final AiPreviewGuard previewGuard;
    private final JobGenerationService generationService;
    private final AiTaskRepository taskRepository;

    public JobMaterialSelectionController(AiTaskService taskService,
                                          MaterialSelectionConfirmationService confirmationService,
                                          JobDescriptionRepository jobRepository,
                                          JobGenerationService generationService,
                                          AiTaskRepository taskRepository,
                                          MaterialSelectionContextBuilder contextBuilder,
                                          AiPreviewService previewService,
                                          AiPreviewGuard previewGuard) {
        this.taskService = taskService;
        this.confirmationService = confirmationService;
        this.jobRepository = jobRepository;
        this.generationService = generationService;
        this.taskRepository = taskRepository;
        this.contextBuilder = contextBuilder;
        this.previewService = previewService;
        this.previewGuard = previewGuard;
    }

    @PostMapping("/select-materials-for-job")
    @Transactional
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> select(
            @Valid @RequestBody SelectMaterialsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        Long userId = currentUserId(servletRequest);
        String provided = idempotencyKey == null ? null : idempotencyKey.trim();
        if (provided != null && !provided.isEmpty() && provided.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key 最长 128 字符");
        }
        String key = provided == null || provided.isEmpty() ? UUID.randomUUID().toString() : provided;
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

        // TODO(R32 AC-R32-9)：令牌强制暂未开启 —— 开启后 JobGenerationControllerIT 等既有用例
        // 需要一并改成"先预览再提交"。预览端点已可用，前端也已接线，只差把这道闸落下。
        CreateAiTaskRequest taskRequest = new CreateAiTaskRequest(AiTaskType.JOB_MATERIAL_SELECTION,
                input, null, jobId, null, null, null, request.resumeTitle());
        AiTaskStatusResponse task = taskService.create(taskRequest,
                key,
                userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(servletRequest)));
    }

    /**
     * 选材的发送前预览（R32 AC-R32-8）：返回**将要发送的确切 messages** 与令牌。
     *
     * <p>只读：不落库、不调 provider。粘贴 JD 时用**未持久化**的瞬时岗位对象参与组装 ——
     * 字段与创建时持久化的那条相同，因此 prompt 与 worker 组装结果一致。
     */
    @PostMapping("/select-materials-for-job/preview")
    public ApiResponse<AiPreviewService.PreviewResponse> preview(
            @Valid @RequestBody SelectMaterialsRequest request,
            HttpServletRequest servletRequest) {
        Long userId = currentUserId(servletRequest);
        JobDescription job = resolveJobForPreview(request, userId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("includedMaterialIds", safe(request.includedMaterialIds()));
        input.put("preferredMaterialIds", safe(request.preferredMaterialIds()));
        input.put("excludedMaterialIds", safe(request.excludedMaterialIds()));

        MaterialSelectionContextBuilder.Context context = contextBuilder.build(userId, job, input);
        if (!context.hasCandidates()) {
            throw new BusinessException(ErrorCode.VALIDATION, "没有可用素材：请先补充职业资料再发起");
        }
        return ApiResponse.success(previewService.preview(new AiPreviewService.PreviewRequest(
                        AiTaskType.JOB_MATERIAL_SELECTION, context.providerInput(), false)),
                traceId(servletRequest));
    }

    /** 预览用：绝不写库；粘贴 JD 时构造瞬时对象。 */
    private JobDescription resolveJobForPreview(SelectMaterialsRequest request, Long userId) {
        if (request.jobDescriptionId() != null) {
            return jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        }
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "A job description is required");
        }
        JobDescription job = new JobDescription();
        job.setUserId(userId);
        job.setTitle(request.positionTitle() == null || request.positionTitle().isBlank()
                ? "Temporary job" : request.positionTitle());
        job.setCompanyName(request.companyName());
        job.setJdText(request.jdText());
        return job;
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
