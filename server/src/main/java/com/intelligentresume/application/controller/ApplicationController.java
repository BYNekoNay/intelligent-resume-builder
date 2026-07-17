package com.intelligentresume.application.controller;

import com.intelligentresume.application.dto.ApplicationCreateRequest;
import com.intelligentresume.application.dto.ApplicationResponse;
import com.intelligentresume.application.dto.ApplicationStatusRequest;
import com.intelligentresume.application.service.ApplicationService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;
    public ApplicationController(ApplicationService service) { this.service = service; }
    @PostMapping public ApiResponse<ApplicationResponse> create(@Valid @RequestBody ApplicationCreateRequest request, HttpServletRequest http) {
        return ApiResponse.success(service.create(request, userId(http)), traceId(http));
    }
    @GetMapping public ApiResponse<List<ApplicationResponse>> list(HttpServletRequest http) {
        return ApiResponse.success(service.list(userId(http)), traceId(http));
    }
    @GetMapping("/{id}") public ApiResponse<ApplicationResponse> get(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.success(service.get(id, userId(http)), traceId(http));
    }
    @PutMapping("/{id}") public ApiResponse<ApplicationResponse> update(@PathVariable Long id,
            @Valid @RequestBody ApplicationCreateRequest request, HttpServletRequest http) {
        return ApiResponse.success(service.update(id, request, userId(http)), traceId(http));
    }
    @PatchMapping("/{id}/status") public ApiResponse<ApplicationResponse> update(@PathVariable Long id,
            @Valid @RequestBody ApplicationStatusRequest request, HttpServletRequest http) {
        return ApiResponse.success(service.updateStatus(id, request, userId(http)), traceId(http));
    }
    private Long userId(HttpServletRequest http) {
        Object value = http.getAttribute("currentUserId");
        if (value == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) value;
    }
    private String traceId(HttpServletRequest http) { return (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE); }
}
