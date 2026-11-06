package com.intelligentresume.interview.controller;

import com.intelligentresume.auth.jwt.JwtAuthenticationFilter;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.interview.dto.*;
import com.intelligentresume.interview.service.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 面试接口入口（统一前缀 {@code /api/interviews}）。
 *
 * <p>【小白导读】
 * ① 业务问题：模拟面试全部 HTTP 接口的门牌号——开始、看状态、交答案、重试、转规则、
 *    结束、看报告、历史列表、薄弱项练习及其预览。
 * ② 链路位置：最外层。只做参数校验（含 Idempotency-Key 头）后透传给 {@link InterviewService}；
 *    用户身份不来自请求参数，而是鉴权过滤器塞进 request attribute。
 * ③ 改坏它：所有面试接口直接 401/404/400，前端整个面试流程点不动。
 *
 * <p>要点：
 * <ul>
 *   <li>Controller 只做参数校验与透传，业务全部在 {@link InterviewService}；不要在这里加状态判断。</li>
 *   <li>{@code currentUserId} 由鉴权过滤器写入 request attribute，缺失即视为未登录（401），
 *       不要从请求参数取用户 id。</li>
 *   <li>会创建 attempt 的写操作（start / answer 含规则模式 / follow-up）要求 {@code Idempotency-Key}
 *       （≤64 字符），服务端用「键 + 请求指纹」保证重放安全；retry / continue-with-rules / finish 不要求。</li>
 *   <li>{@code /answer} 需要先读一次状态来决定走 AI 还是规则分支（见方法内注释）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/interviews")
@Validated
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<InterviewSessionSummaryResponse>> listHistory(
            @RequestParam(required = false) Long jobDescriptionId, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.listHistory(currentUserId(httpRequest), jobDescriptionId), traceId(httpRequest));
    }

    @PostMapping("/start")
    public ApiResponse<InterviewStateResponse> start(@Valid @RequestBody StartInterviewRequest request,
                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
                                                      HttpServletRequest httpRequest) {
        return ApiResponse.success(service.start(request, currentUserId(httpRequest), idempotencyKey.trim()), traceId(httpRequest));
    }

    /** 追问练习是异步任务：立即返回 202 + taskId，前端轮询 {@code /api/ai/tasks/{id}} 取结果。 */
    @PostMapping("/{id}/follow-up")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> createFollowUp(
            @PathVariable Long id,
            @Valid @RequestBody FollowUpPracticeRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @RequestHeader(value = AiPreviewGuard.HEADER, required = false) String previewToken,
            HttpServletRequest httpRequest) {
        AiTaskStatusResponse task = service.createFollowUp(id, request.weakness(),
                currentUserId(httpRequest), idempotencyKey.trim(), previewToken);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(httpRequest)));
    }

    /**
     * 追问练习的发送前预览（R32）：返回**将要发送的确切 messages** 与 `previewToken`。
     * 只读：不落库、不调 provider、不消耗配额，不要求 `Idempotency-Key`。
     */
    @PostMapping("/{id}/follow-up/preview")
    public ApiResponse<AiPreviewService.PreviewResponse> previewFollowUp(
            @PathVariable Long id,
            @Valid @RequestBody FollowUpPracticeRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.previewFollowUp(id, request.weakness(), currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<InterviewStateResponse> getState(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.getState(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /**
     * 提交回答。这里必须先读一次状态：规则降级后作答要写规则分（同步），AI 模式则要提交后台评估（异步），
     * 两者返回结构相同但行为完全不同。
     *
     * <p>注意这次 {@code getState} 不是纯读：处于 AI 进行中时它会做陈旧接管，因此本请求可能顺带把
     * 一个超时的 attempt 判死。这是有意复用的兜底路径，不要改成更轻的查询。
     */
    @PostMapping("/{id}/answer")
    public ApiResponse<InterviewStateResponse> answer(@PathVariable Long id,
                                                       @Valid @RequestBody AnswerInterviewRequest request,
                                                       @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
                                                       HttpServletRequest httpRequest) {
        if (service.getState(id, currentUserId(httpRequest)).getExecutionMode() == com.intelligentresume.interview.domain.ExecutionMode.RULE) {
            return ApiResponse.success(service.ruleAnswer(id, request.getAnswer().trim(), currentUserId(httpRequest), idempotencyKey.trim()), traceId(httpRequest));
        }
        return ApiResponse.success(service.answer(id, request.getAnswer().trim(), currentUserId(httpRequest), idempotencyKey.trim()), traceId(httpRequest));
    }

    /** 重试最近一次失败的 AI 操作；立即返回进行中状态，结果靠轮询。 */
    @PostMapping("/{id}/ai/retry")
    public ApiResponse<InterviewStateResponse> retry(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.retryAi(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/continue-with-rules")
    public ApiResponse<InterviewStateResponse> continueWithRules(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.continueWithRules(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<InterviewStateResponse> finish(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.finish(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /** 复盘报告：只读聚合，不触发 AI 调用；会话未完成时返回 409。 */
    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.report(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /** 当前登录用户由过滤器注入 request attribute；取不到时按未登录处理，绝不回退到请求参数。 */
    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.CURRENT_USER_ID_ATTRIBUTE);
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
