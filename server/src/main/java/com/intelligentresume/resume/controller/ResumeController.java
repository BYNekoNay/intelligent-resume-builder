package com.intelligentresume.resume.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.*;
import com.intelligentresume.resume.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ResumeDetail>> create(
            @Valid @RequestBody CreateResumeRequest request, HttpServletRequest httpRequest) {
        ResumeDetail detail = resumeService.create(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<List<ResumeSummary>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    /**
     * 查询某 JD 关联的岗位简历列表。用于"同 JD 再次生成"时判断新建/更新。
     */
    @GetMapping("/by-jd/{jdId}")
    public ApiResponse<List<ResumeSummary>> listByJobDescription(
            @PathVariable Long jdId, HttpServletRequest httpRequest) {
        return ApiResponse.success(
                resumeService.listByJobDescription(jdId, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeDetail> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResumeDetail> update(
            @PathVariable Long id, @Valid @RequestBody UpdateResumeRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                resumeService.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        resumeService.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PatchMapping("/{id}/current-version")
    public ApiResponse<Void> setCurrentVersion(
            @PathVariable Long id, @RequestBody Map<String, Long> body,
            HttpServletRequest httpRequest) {
        Long versionId = body.get("versionId");
        if (versionId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少 versionId");
        }
        resumeService.setCurrentVersion(id, versionId, currentUserId(httpRequest));
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
