package com.intelligentresume.communication.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.communication.dto.CommunicationResponse;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.communication.service.CommunicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/communications")
public class CommunicationController {
    private final CommunicationService service;
    public CommunicationController(CommunicationService service) { this.service = service; }

    @PostMapping("/generate")
    public ApiResponse<CommunicationResponse> generate(@Valid @RequestBody GenerateCommunicationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.generate(request, currentUserId(httpRequest)), traceId(httpRequest));
    }
    private Long currentUserId(HttpServletRequest request) { Object value = request.getAttribute("currentUserId"); return value instanceof Long id ? id : null; }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
