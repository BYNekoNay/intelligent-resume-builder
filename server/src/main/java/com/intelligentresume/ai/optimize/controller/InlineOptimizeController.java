package com.intelligentresume.ai.optimize.controller;

import com.intelligentresume.ai.optimize.dto.InlineOptimizeRequest;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 内联能力的两个创建入口：{@code /api/ai/inline-optimize}（改表达）与
 * {@code /api/ai/achievement-guidance}（引导补量化成果）。
 *
 * <h2>这两个接口在做什么（人话）</h2>
 * 用户在简历编辑器里选中一段文字 → 这里把「哪份简历、哪一节、什么内容、关联哪个 JD」打包成一个任务 →
 * 立刻返回 <b>202 + taskId</b>，前端轮询结果。两个端点逻辑相同，只有任务类型不同。
 *
 * <h2>三个前置检查，顺序不能换</h2>
 * <ol>
 *   <li><b>幂等键必填</b>：缺失、空白或超过 128 字符直接 400。编辑器容易重复触发，
 *       没有幂等键就会为一句话创建好几个 AI 任务，白烧当日配额；</li>
 *   <li><b>资源归属</b>：简历版本必须属于当前用户且未删除，JD 同理。这一步必须在建任务<b>之前</b>，
 *       否则别人的简历内容会被拼进你的提示词；</li>
 *   <li><b>再建任务</b>：只有前两步都通过才落库。</li>
 * </ol>
 *
 * <h2>重试为什么不带幂等键</h2>
 * {@code /tasks/{id}/retry} 重跑的是**已经落库的同一个任务**，不产生新记录，所以不需要键。
 *
 * <p>按 R32 约定，创建类端点还应携带 {@code X-AI-Preview-Token}；本类不直接读取该头，
 * 改链路时不要在别处绕开它单独放行。
 */
@RestController
@RequestMapping("/api/ai")
public class InlineOptimizeController {

    private final AiTaskService taskService;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JobDescriptionRepository jobDescriptionRepository;

    public InlineOptimizeController(AiTaskService taskService, ResumeVersionRepository resumeVersionRepository,
                                    JobDescriptionRepository jobDescriptionRepository) {
        this.taskService = taskService;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
    }

    @PostMapping("/inline-optimize")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> optimize(
            @Valid @RequestBody InlineOptimizeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        String key = requireIdempotencyKey(idempotencyKey);
        Long userId = currentUserId(httpRequest);
        validateOwnedResources(request, userId);
        Map<String, Object> input = toInputMap(request);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.INLINE_OPTIMIZE, input, null, null, null, null, null, null);
        AiTaskStatusResponse resp = taskService.create(req, key, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(resp, traceId(httpRequest)));
    }

    @PostMapping("/achievement-guidance")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> guide(
            @Valid @RequestBody InlineOptimizeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        String key = requireIdempotencyKey(idempotencyKey);
        Long userId = currentUserId(httpRequest);
        validateOwnedResources(request, userId);
        Map<String, Object> input = toInputMap(request);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.ACHIEVEMENT_GUIDANCE, input, null, null, null, null, null, null);
        AiTaskStatusResponse resp = taskService.create(req, key, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(resp, traceId(httpRequest)));
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Idempotency-Key is required and must be at most 128 characters");
        }
        return idempotencyKey.trim();
    }

    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> retry(
            @PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        AiTaskStatusResponse resp = taskService.retry(id, userId);
        return ResponseEntity.ok(ApiResponse.success(resp, traceId(httpRequest)));
    }

    private Map<String, Object> toInputMap(InlineOptimizeRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resumeVersionId", request.getResumeVersionId());
        map.put("section", request.getSection());
        map.put("content", request.getContent());
        if (request.getJdContext() != null) {
            map.put("jdContext", request.getJdContext());
        }
        if (request.getJobDescriptionId() != null) {
            map.put("jobDescriptionId", request.getJobDescriptionId());
        }
        return map;
    }

    /** 两个资源都按「不存在」处理（而不是 403）：不告诉调用方"这个 id 存在，只是不属于你"。 */
    private void validateOwnedResources(InlineOptimizeRequest request, Long userId) {
        resumeVersionRepository.findByIdAndCreatedByAndDeletedAtIsNull(request.getResumeVersionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume version not found"));
        if (request.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(request.getJobDescriptionId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
