package com.intelligentresume.ai.confirmation.controller;

import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.dto.ConfirmResponse;
import com.intelligentresume.ai.confirmation.dto.RejectRequest;
import com.intelligentresume.ai.confirmation.service.ConfirmationService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 来源确认控制器。
 *
 * <p>路由：
 * <ul>
 *   <li>POST {@code /api/ai/tasks/{id}/confirm} — 用户逐项确认</li>
 *   <li>POST {@code /api/ai/tasks/{id}/reject} — 用户拒绝</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/tasks")
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    /**
     * 确认 AI 任务结果。需要 Idempotency-Key 请求头。
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<ConfirmResponse>> confirm(
            @PathVariable Long id,
            @Valid @RequestBody ConfirmRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        ConfirmResponse response = confirmationService.confirm(id, request, idempotencyKey, userId);
        return ResponseEntity.ok(ApiResponse.success(response, traceId(httpRequest)));
    }

    /**
     * 拒绝 AI 任务结果。
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectRequest request,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        confirmationService.reject(id, request, userId);
        return ResponseEntity.ok(ApiResponse.success(null, traceId(httpRequest)));
    }

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
}
