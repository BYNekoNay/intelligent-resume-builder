package com.intelligentresume.jobdescription.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.dto.JobDescriptionResponse;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobDescriptionController {

    private final JobDescriptionService service;

    public JobDescriptionController(JobDescriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<JobDescriptionResponse> create(@Valid @RequestBody JobDescriptionCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.success(service.create(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping
    public ApiResponse<List<JobDescriptionResponse>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobDescriptionResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}")
    public ApiResponse<JobDescriptionResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody JobDescriptionCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<JobDescriptionResponse> parse(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.parse(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.softDelete(id, currentUserId(httpRequest));
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