package com.intelligentresume.resume.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.RestoreResumeVersionRequest;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.service.ResumeVersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ResumeVersionController {

    private final ResumeVersionService versionService;

    public ResumeVersionController(ResumeVersionService versionService) {
        this.versionService = versionService;
    }

    @PostMapping("/api/resumes/{resumeId}/versions")
    public ResponseEntity<ApiResponse<ResumeVersionDetail>> save(
            @PathVariable Long resumeId, @Valid @RequestBody SaveVersionRequest request,
            HttpServletRequest httpRequest) {
        ResumeVersionDetail detail = versionService.save(resumeId, request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping("/api/resumes/{resumeId}/versions")
    public ApiResponse<List<ResumeVersionSummary>> list(
            @PathVariable Long resumeId,
            @RequestParam(defaultValue = "false") boolean archived,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                versionService.listByResume(resumeId, archived, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/restore")
    public ResponseEntity<ApiResponse<ResumeVersionDetail>> restore(
            @PathVariable Long resumeId, @PathVariable Long versionId,
            @Valid @RequestBody(required = false) RestoreResumeVersionRequest request,
            HttpServletRequest httpRequest) {
        ResumeVersionDetail detail = versionService.restore(resumeId, versionId, request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/archive")
    public ApiResponse<Void> archive(
            @PathVariable Long resumeId, @PathVariable Long versionId, HttpServletRequest httpRequest) {
        versionService.archive(resumeId, versionId, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/unarchive")
    public ApiResponse<Void> unarchive(
            @PathVariable Long resumeId, @PathVariable Long versionId, HttpServletRequest httpRequest) {
        versionService.unarchive(resumeId, versionId, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @GetMapping("/api/resume-versions/{id}")
    public ApiResponse<ResumeVersionDetail> get(
            @PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(versionService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
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
