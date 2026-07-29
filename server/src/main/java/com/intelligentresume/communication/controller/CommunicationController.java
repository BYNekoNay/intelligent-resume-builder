package com.intelligentresume.communication.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.communication.dto.CommunicationResponse;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.communication.service.CommunicationService;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/communications")
public class CommunicationController {
    private final CommunicationService service;
    public CommunicationController(CommunicationService service) { this.service = service; }

    @PostMapping("/generate")
    public ApiResponse<CommunicationResponse> generate(@Valid @RequestBody GenerateCommunicationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.generate(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/ai-generate")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> generateWithAi(
            @Valid @RequestBody GenerateCommunicationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少 Idempotency-Key");
        }
        AiTaskStatusResponse task = service.generateWithAi(request, idempotencyKey.trim(), currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(httpRequest)));
    }
    private Long currentUserId(HttpServletRequest request) { Object value = request.getAttribute("currentUserId"); return value instanceof Long id ? id : null; }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
