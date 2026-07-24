package com.intelligentresume.ai.task.controller;

import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 任务控制器。
 */
@RestController
@RequestMapping("/api/ai")
public class AiTaskController {

    private final AiTaskService taskService;
    private final JobGenerationService jobGenerationService;
    private final JobDescriptionRepository jdRepository;

    public AiTaskController(AiTaskService taskService,
                            JobGenerationService jobGenerationService,
                            JobDescriptionRepository jdRepository) {
        this.taskService = taskService;
        this.jobGenerationService = jobGenerationService;
        this.jdRepository = jdRepository;
    }

    /**
     * 创建 AI 任务(为职位生成简历)。返回 202 Accepted。
     * 读取 Idempotency-Key 请求头;缺失时自动生成 UUID。
     * JOB_GENERATION 任务在创建前校验资料 ID 归属(跨用户 → 40401)。
     *
     * <p>支持内联 JD：传 jdText 时自动创建 JobDescription 记录。
     * targetResumeId 可选：不传时确认后自动创建岗位简历。
     */
    @PostMapping("/generate-resume-for-job")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> createTask(
            @Valid @RequestBody CreateAiTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);

        // 内联 JD 处理：有 jdText 但无 jobDescriptionId → 自动创建 JD 记录
        if (request.taskType() == AiTaskType.JOB_GENERATION
                && request.jobDescriptionId() == null
                && request.jdText() != null && !request.jdText().isBlank()) {
            JobDescription jd = new JobDescription();
            jd.setUserId(userId);
            jd.setTitle(request.positionTitle() != null ? request.positionTitle()
                    : request.companyName() != null ? request.companyName() : "临时岗位");
            jd.setCompanyName(request.companyName());
            jd.setJdText(request.jdText());
            jd = jdRepository.save(jd);
            request = new CreateAiTaskRequest(
                    request.taskType(), request.input(), request.targetResumeId(),
                    jd.getId(), null, null, null, request.resumeTitle());
        }

        // T07: JOB_GENERATION 任务校验资料 ID 归属
        if (request.taskType() == AiTaskType.JOB_GENERATION && request.input() != null) {
            jobGenerationService.validateMaterialIds(userId,
                    toLongList(request.input().get("includedMaterialIds")),
                    toLongList(request.input().get("preferredMaterialIds")),
                    toLongList(request.input().get("excludedMaterialIds")));
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        }
        AiTaskStatusResponse response = taskService.create(request, idempotencyKey, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response, traceId(httpRequest)));
    }

    /**
     * 查询 AI 任务状态。
     */
    @GetMapping("/tasks/{id}")
    public ApiResponse<AiTaskStatusResponse> getTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(taskService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    // confirm / reject 路由已迁移至 ConfirmationController（T08）

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Number)
                    .map(item -> ((Number) item).longValue())
                    .toList();
        }
        return List.of();
    }
}
