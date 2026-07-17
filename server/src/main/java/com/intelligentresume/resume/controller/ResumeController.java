package com.intelligentresume.resume.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeResponse;
import com.intelligentresume.resume.dto.ResumeTitleUpdateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionResponse;
import com.intelligentresume.resume.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping
    public ApiResponse<ResumeResponse> create(@Valid @RequestBody ResumeCreateRequest request, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ApiResponse.success(resumeService.create(request, userId), traceId(httpRequest));
    }

    @GetMapping
    public ApiResponse<List<ResumeResponse>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResumeResponse> update(@PathVariable Long id, @Valid @RequestBody ResumeTitleUpdateRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.updateTitle(id, request.title(), currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        resumeService.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<ResumeVersionResponse> createVersion(@PathVariable Long id,
                                                            @Valid @RequestBody ResumeVersionCreateRequest request,
                                                            HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.createVersion(id, request, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ResumeVersionResponse>> listVersions(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.listVersions(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ApiResponse<ResumeVersionResponse> getVersion(@PathVariable Long id, @PathVariable Long versionId,
                                                         HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.getVersion(id, versionId, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @PostMapping("/{id}/versions/{versionId}/current")
    public ApiResponse<ResumeResponse> setCurrent(@PathVariable Long id, @PathVariable Long versionId,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.setCurrentVersion(id, versionId, currentUserId(httpRequest)),
                traceId(httpRequest));
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
