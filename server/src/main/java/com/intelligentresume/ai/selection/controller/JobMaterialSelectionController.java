package com.intelligentresume.ai.selection.controller;

import com.intelligentresume.ai.generation.service.JobGenerationPreviewService;
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

/**
 * 选材 / 确认 / 生成三组「预览 + 执行」端点（全部挂在 /api/ai 下）。
 *
 * <p>新人三问：①业务问题：这是"AI 帮用户挑资料→用户确认→AI 生成简历"整条流程的 HTTP 入口，
 * 用户在界面上的每一步动作最终都落到本类的某个端点；②链路位置：最外层——接收请求、
 * 鉴权（从过滤器取 userId）、调 service（确认走 {@code MaterialSelectionConfirmationService}，
 * 选材走 {@code AiTaskService} 建任务，预览走 {@code AiPreviewService}）；③改坏它用户会看到：
 * 接口 500、粘贴 JD 后留下孤儿岗位记录、重复点击创建多个任务、或绕过预览直接发送。
 *
 * <p>术语：幂等键（Idempotency-Key 请求头，客户端生成，防止网络重试导致重复创建任务）、
 * 预览令牌（X-AI-Preview-Token，见 {@code AiPreviewGuard}）。
 *
 * <p>要点：
 * <ul>
 *   <li>每组都是"先 /preview 拿 messages + 令牌 → 再带 {@code X-AI-Preview-Token} 提交"，
 *       预览与提交必须用**同一个 body**：令牌是服务端拿自己组装的 providerInput 重算的，
 *       任何字段变化都会变成 409（CONFLICT）。</li>
 *   <li>{@link #select} 上的 {@code @Transactional} 是为了让"粘贴 JD 时顺带建的临时岗位"与
 *       "建任务"同生共死：令牌校验失败抛异常时，临时岗位一起回滚，不留下孤儿 JD。</li>
 *   <li>幂等策略不对称：发起选材不带 {@code Idempotency-Key} 时生成随机 UUID（等于不幂等）；
 *       而确认选材不带 key 时会退化成确定性的 {@code selection-confirm:<taskId>}。改这里前先确认前端到底带不带。</li>
 *   <li>粘贴 JD 的重放比对（{@link #matchesPastedRequest}）只比 jdText / 标题 / 公司名 / 三个素材 ID 列表，
 *       <b>不比 resumeTitle</b> —— 只改了简历标题的重试会命中幂等，返回旧任务。</li>
 *   <li>用户身份来自过滤器写入的 request attribute，缺失即 401；本类不做任何权限推断。</li>
 * </ul>
 */
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
    private final JobGenerationPreviewService generationPreviewService;
    private final AiTaskRepository taskRepository;

    public JobMaterialSelectionController(AiTaskService taskService,
                                          MaterialSelectionConfirmationService confirmationService,
                                          JobDescriptionRepository jobRepository,
                                          JobGenerationService generationService,
                                          JobGenerationPreviewService generationPreviewService,
                                          AiTaskRepository taskRepository,
                                          MaterialSelectionContextBuilder contextBuilder,
                                          AiPreviewService previewService,
                                          AiPreviewGuard previewGuard) {
        this.taskService = taskService;
        this.confirmationService = confirmationService;
        this.jobRepository = jobRepository;
        this.generationService = generationService;
        this.generationPreviewService = generationPreviewService;
        this.taskRepository = taskRepository;
        this.contextBuilder = contextBuilder;
        this.previewService = previewService;
        this.previewGuard = previewGuard;
    }

    /**
     * 发起选材任务（返回 202 + 任务状态）。
     *
     * <p>顺序：幂等预检（仅粘贴 JD 分支）→ 建临时岗位 → 归属/素材校验 → 预览令牌校验 → 建任务。
     * 令牌校验放在所有落库之后、建任务之前，靠外层事务回滚保证"被拒时不留脏数据"。
     */
    @PostMapping("/select-materials-for-job")
    @Transactional
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> select(
            @Valid @RequestBody SelectMaterialsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = AiPreviewGuard.HEADER, required = false) String previewToken,
            HttpServletRequest servletRequest) {
        Long userId = currentUserId(servletRequest);
        String provided = idempotencyKey == null ? null : idempotencyKey.trim();
        if (provided != null && !provided.isEmpty() && provided.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key 最长 128 字符");
        }
        // 未带 Idempotency-Key 时用随机 UUID 兜底 —— 即"不幂等"，每次调用都会新建一个任务与（可能）一条临时岗位
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
        JobDescription previewJob = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        generationService.validateMaterialIds(userId, request.includedMaterialIds(),
                request.preferredMaterialIds(), request.excludedMaterialIds());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("includedMaterialIds", safe(request.includedMaterialIds()));
        input.put("preferredMaterialIds", safe(request.preferredMaterialIds()));
        input.put("excludedMaterialIds", safe(request.excludedMaterialIds()));

        // R32 AC-R32-9：必须先看过 /select-materials-for-job/preview 的确切内容并确认。
        // 校验用服务端**自己组装**的 providerInput 重算令牌 —— 客户端伪造不了"预览过"。
        MaterialSelectionContextBuilder.Context previewContext = contextBuilder.build(userId, previewJob, input);
        previewGuard.require(AiTaskType.JOB_MATERIAL_SELECTION, previewContext.providerInput(), previewToken);
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
     *
     * <p>没有候选素材时返回 400（而不是给一份空 prompt 的预览）：worker 侧遇到空候选同样
     * 不会发请求，两边行为对齐。
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

    /**
     * 确认选材 → 创建 JOB_GENERATION 子任务，返回 202 + 子任务状态。
     *
     * <p>除了任务归属，所有校验（同意书 / 幂等 / 状态戳 / 令牌 / 配额）都在
     * {@link MaterialSelectionConfirmationService#confirm} 里，控制器只做身份提取与响应包装。
     */
    @PostMapping("/tasks/{taskId}/confirm-materials")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> confirm(
            @PathVariable Long taskId,
            @Valid @RequestBody ConfirmMaterialsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = AiPreviewGuard.HEADER, required = false) String previewToken,
            HttpServletRequest servletRequest) {
        AiTask task = confirmationService.confirm(taskId, request, idempotencyKey, previewToken,
                currentUserId(servletRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(taskService.toResponse(task), traceId(servletRequest)));
    }

    /**
     * 生成任务的发送前预览（R32 AC-R32-8）：返回**将要发送的确切 messages** 与 `previewToken`。
     *
     * <p>只读：不落库、不调 provider。请求体与 confirm 相同，因此客户端可以用同一个 body
     * 先预览、再带着令牌提交。
     */
    @PostMapping("/tasks/{taskId}/confirm-materials/preview")
    public ApiResponse<AiPreviewService.PreviewResponse> previewConfirmation(
            @PathVariable Long taskId,
            @Valid @RequestBody ConfirmMaterialsRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(
                generationPreviewService.preview(taskId, request, currentUserId(servletRequest)),
                traceId(servletRequest));
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
