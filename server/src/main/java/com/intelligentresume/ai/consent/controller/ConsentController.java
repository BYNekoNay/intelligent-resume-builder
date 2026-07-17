package com.intelligentresume.ai.consent.controller;

import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/consent")
public class ConsentController {

    private final ConsentService service;

    public ConsentController(ConsentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ConsentResponse> current(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.current(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping
    public ApiResponse<ConsentResponse> grant(@Valid @RequestBody ConsentRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.success(service.grant(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping
    public ApiResponse<ConsentResponse> withdraw(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.withdraw(currentUserId(httpRequest)), traceId(httpRequest));
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
