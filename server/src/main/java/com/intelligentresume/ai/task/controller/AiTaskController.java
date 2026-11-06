package com.intelligentresume.ai.task.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskContinuationResponse;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 任务的通用 HTTP 入口:预览、创建、查询、续办列表、清空历史。
 *
 * <p>【业务问题】前端要发起一次 AI 操作时，HTTP 层需要做四件事：先让用户看到"将要发送什么"
 * （预览）、再收下提交并立刻返回 taskId（创建）、之后反复来问结果（查询）、最后能清理历史。</p>
 *
 * <p>【链路位置】前端 ↔ 本控制器 ↔ {@code AiTaskService}(创建/查询/重试相关) 与
 * {@code AiPreviewService}(预览与令牌校验)。它只做入口校验与编排，不含业务规则。</p>
 *
 * <p>【改坏它会怎样】预览令牌校验被绕过等于"发送前确认"形同虚设；userId 改从请求参数取
 * 会造成越权；input 大小限制去掉会让超大 JSON 直接进提示词与任务快照。</p>
 *
 * <p>要点 / 红线:</p>
 * <ul>
 *   <li>创建走"两步确认":先 {@code POST /tasks/preview} 拿到 previewToken,再带 token 提交;
 *       缺少或载荷已变都会被拒,且拒绝发生在 service 之前,不会留下 ai_task 行(R32)。</li>
 *   <li>只有 {@code genericEndpointAllowed=true} 的任务类型能走这里;其余必须用各自的领域端点,
 *       否则会得到 VALIDATION。</li>
 *   <li>input 按序列化后的字节数设上限(默认 256KB),超限直接拒绝而不是截断 ——
 *       截断会静默丢数据并污染 prompt。</li>
 *   <li>userId 只从 {@code currentUserId} 请求属性取(由认证过滤器写入),不信任任何请求参数。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiTaskController {

    private final AiTaskService taskService;
    private final AiPreviewService previewService;
    private final ObjectMapper objectMapper;
    private final long maxInputJsonBytes;

    public AiTaskController(AiTaskService taskService, AiPreviewService previewService,
                            ObjectMapper objectMapper,
                            @Value("${app.ai.max-input-json-bytes:262144}") long maxInputJsonBytes) {
        this.taskService = taskService;
        this.previewService = previewService;
        this.objectMapper = objectMapper;
        this.maxInputJsonBytes = maxInputJsonBytes;
    }

    /**
     * 发送前预览（R32）：返回**将要发送的确切 messages** 与预览令牌。
     *
     * <p>只读：不写库、不调 provider。想真正发起任务，必须带上看完这份预览后拿到的
     * {@code previewToken}（见 {@link #createTask}）。
     */
    @PostMapping("/tasks/preview")
    public ApiResponse<AiPreviewService.PreviewResponse> previewTasks(
            @RequestBody AiPreviewService.PreviewRequest request, HttpServletRequest servletRequest) {
        if (request == null || request.taskType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "taskType 不能为空");
        }
        AiTaskCapabilityRegistry.requireRegistered(request.taskType());
        requireInputWithinSizeLimit(request.input());
        return ApiResponse.success(previewService.preview(request),
                (String) servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }

    /**
     * 创建任务并立即返回 202（Accepted = 已受理，不代表有结果）。
     *
     * <p>校验顺序:任务类型已登记 → 允许走通用端点 → input 大小 → 预览令牌 → 幂等键长度。
     * 幂等键缺省时生成随机 UUID(等于"每次都是新请求"),不会因此报错。</p>
     *
     * <p>返回的是 PENDING 状态,不是最终结果;客户端需要轮询（每隔一两秒调一次
     * {@code GET /tasks/{id}}，直到状态变成 SUCCESS 或 FAILED）。</p>
     */
    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> createTask(
            @Valid @RequestBody CreateAiTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        AiTaskCapabilityRegistry.Descriptor capability = AiTaskCapabilityRegistry.requireRegistered(request.taskType());
        if (!capability.genericEndpointAllowed()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "This AI task must start from its domain endpoint");
        }
        requireInputWithinSizeLimit(request.input());
        requirePreviewConfirmed(request.taskType(), request.input());
        String provided = idempotencyKey == null ? null : idempotencyKey.trim();
        if (provided != null && !provided.isEmpty() && provided.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key 最长 128 字符");
        }
        String key = provided == null || provided.isEmpty()
                ? UUID.randomUUID().toString() : provided;
        AiTaskStatusResponse task = taskService.create(request, key, currentUserId(servletRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task,
                        (String) servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE)));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<AiTaskStatusResponse> getTask(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.success(taskService.get(id, currentUserId(request)),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }

    @GetMapping("/tasks/continuations")
    public ApiResponse<List<AiTaskContinuationResponse>> listContinuations(HttpServletRequest request) {
        return ApiResponse.success(taskService.listContinuations(currentUserId(request)),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }

    /**
     * 清空本人的 AI 任务历史（ideation #26）。只删终态且非待确认的任务，
     * 返回实际删除条数；进行中的任务与待确认任务保留。
     */
    @DeleteMapping("/tasks/history")
    public ApiResponse<Integer> clearHistory(HttpServletRequest request) {
        return ApiResponse.success(taskService.deleteHistory(currentUserId(request)),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }

    /**
     * R32：「需显式确认」。没有预览令牌或令牌与当前载荷不符，一律拒绝，且**不落库** ——
     * 拒绝发生在 service 之前，所以不会产生 ai_task 行。
     */
    private void requirePreviewConfirmed(com.intelligentresume.ai.task.domain.AiTaskType taskType,
                                         java.util.Map<String, Object> input) {
        AiPreviewService.TokenCheck check = previewService.verify(taskType, input);
        if (check == AiPreviewService.TokenCheck.MISSING) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "请先调用 /api/ai/tasks/preview 预览将要发送的内容并确认（缺少 previewToken）");
        }
        if (check == AiPreviewService.TokenCheck.STALE) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "预览内容与本次提交不一致（输入或脱敏选项已改变）：请重新预览后再提交");
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        Object id = request.getAttribute("currentUserId");
        if (id == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) id;
    }

    /**
     * #44：通用端点 input 的语义大小约束。此前只受容器请求体上限约束，
     * 超大 JSON 会直接写入任务快照并进入提示词。按序列化字节数拒绝，不做截断（截断会静默丢数据）。
     */
    private void requireInputWithinSizeLimit(Map<String, Object> input) {
        if (input == null) return;
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(input).length;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "输入内容无法序列化");
        }
        if (bytes > maxInputJsonBytes) {
            throw new BusinessException(ErrorCode.VALIDATION, "输入内容超过大小上限");
        }
    }
}
