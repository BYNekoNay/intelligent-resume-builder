package com.intelligentresume.ats.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.dto.AtsCheckResponse;
import com.intelligentresume.ats.service.AtsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ats")
public class AtsController {
    private final AtsService service;

    public AtsController(AtsService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ApiResponse<AtsCheckResponse> check(@Valid @RequestBody AtsCheckRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.check(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("currentUserId");
        return value instanceof Long id ? id : null;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
