package com.intelligentresume.jobdescription.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.dto.*;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * JD 管理控制器。
 *
 * <p>路由与前端 {@code jobDescription.ts} 契约一致:
 * PATCH 更新;POST /{id}/parse 返回完整 JobDescriptionDetail。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobDescriptionController {

    private final JobDescriptionService service;

    public JobDescriptionController(JobDescriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JobDescriptionDetail>> create(
            @Valid @RequestBody CreateJobDescriptionRequest request, HttpServletRequest httpRequest) {
        JobDescriptionDetail detail = service.create(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<List<JobDescriptionSummary>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobDescriptionDetail> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}")
    public ApiResponse<JobDescriptionDetail> update(
            @PathVariable Long id, @Valid @RequestBody UpdateJobDescriptionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<JobDescriptionDetail> parse(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.parse(id, currentUserId(httpRequest)), traceId(httpRequest));
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
