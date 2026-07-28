package com.intelligentresume.interview.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.interview.dto.*;
import com.intelligentresume.interview.service.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) { this.service = service; }

    @PostMapping("/start")
    public ApiResponse<StartInterviewResponse> start(@Valid @RequestBody StartInterviewRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.start(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<AnswerInterviewResponse> answer(@PathVariable Long id, @Valid @RequestBody AnswerInterviewRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.answer(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.report(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("currentUserId");
        return value instanceof Long id ? id : null;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
