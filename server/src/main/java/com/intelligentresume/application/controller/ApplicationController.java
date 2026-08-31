package com.intelligentresume.application.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.application.dto.*;
import com.intelligentresume.application.service.ApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ApplicationResponse>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ApplicationResponse>> create(@Valid @RequestBody CreateApplicationRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(request, currentUserId(httpRequest)), traceId(httpRequest)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ApplicationResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateApplicationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ApplicationResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateApplicationStatusRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.updateStatus(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.delete(id, currentUserId(httpRequest));
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
