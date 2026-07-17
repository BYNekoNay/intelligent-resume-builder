package com.intelligentresume.interview.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.dto.InterviewAnswerAssetCreateRequest;
import com.intelligentresume.interview.dto.InterviewAnswerAssetResponse;
import com.intelligentresume.interview.service.InterviewAnswerAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/interview-answer-assets")
public class InterviewAnswerAssetController {
    private final InterviewAnswerAssetService service;
    public InterviewAnswerAssetController(InterviewAnswerAssetService service) { this.service = service; }
    @PostMapping public ApiResponse<InterviewAnswerAssetResponse> create(@Valid @RequestBody InterviewAnswerAssetCreateRequest request, HttpServletRequest http) {
        return ApiResponse.success(service.create(request, userId(http)), traceId(http));
    }
    @GetMapping public ApiResponse<List<InterviewAnswerAssetResponse>> list(
            @RequestParam(required = false) Long jobDescriptionId,
            @RequestParam(required = false) String keyword,
            HttpServletRequest http) {
        return ApiResponse.success(service.list(userId(http), jobDescriptionId, keyword), traceId(http));
    }
    private Long userId(HttpServletRequest http) { Object value = http.getAttribute("currentUserId"); if (value == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED); return (Long) value; }
    private String traceId(HttpServletRequest http) { return (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE); }
}
