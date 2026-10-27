package com.intelligentresume.ats.controller;

import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.dto.AtsCheckResponse;
import com.intelligentresume.ats.service.AtsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;

@RestController
@RequestMapping("/api/ats")
public class AtsController {
    private final AtsService service;

    public AtsController(AtsService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ApiResponse<AtsCheckResponse> check(@Valid @RequestBody AtsCheckRequest request,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @RequestHeader(value = AiPreviewGuard.HEADER, required = false) String previewToken,
                                                HttpServletRequest httpRequest) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key is required and must be at most 128 characters");
        }
        return ApiResponse.success(service.check(request, idempotencyKey.trim(), currentUserId(httpRequest), previewToken),
                traceId(httpRequest));
    }

    @GetMapping("/checks/{id}")
    public ApiResponse<AtsCheckResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/checks/{id}/ai-retry")
    public ApiResponse<AtsCheckResponse> retryAi(@PathVariable Long id,
                                                  @RequestHeader(value = AiPreviewGuard.HEADER, required = false) String previewToken,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.success(service.retryAi(id, currentUserId(httpRequest), previewToken), traceId(httpRequest));
    }

    /**
     * ATS 分析的发送前预览（R32）：按 checkId 组装，返回**将要发送的确切 messages** 与 `previewToken`。
     * 只读：不落库、不调 provider、不消耗配额、不改检查状态、不要求 `Idempotency-Key`。
     */
    @PostMapping("/checks/{id}/preview")
    public ApiResponse<AiPreviewService.PreviewResponse> preview(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.preview(id, currentUserId(httpRequest)), traceId(httpRequest));
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
