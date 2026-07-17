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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/tasks/{id}")
public class ConfirmationController {

    private final ConfirmationService service;

    public ConfirmationController(ConfirmationService service) {
        this.service = service;
    }

    @PostMapping("/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable Long id,
                                                @Valid @RequestBody ConfirmRequest request,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                HttpServletRequest httpRequest) {
        return ApiResponse.success(service.confirm(id, request, idempotencyKey, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @PostMapping("/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                    @Valid @RequestBody RejectRequest request,
                                    HttpServletRequest httpRequest) {
        service.reject(id, request, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
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