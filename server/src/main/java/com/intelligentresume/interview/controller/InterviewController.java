package com.intelligentresume.interview.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.interview.dto.*;
import com.intelligentresume.interview.service.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
@Validated
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<InterviewSessionSummaryResponse>> listHistory(
            @RequestParam(required = false) Long jobDescriptionId, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.listHistory(currentUserId(httpRequest), jobDescriptionId), traceId(httpRequest));
    }

    @PostMapping("/start")
    public ApiResponse<InterviewStateResponse> start(@Valid @RequestBody StartInterviewRequest request,
                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
                                                      HttpServletRequest httpRequest) {
        return ApiResponse.success(service.start(request, currentUserId(httpRequest), idempotencyKey), traceId(httpRequest));
    }

    @PostMapping("/{id}/follow-up")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> createFollowUp(
            @PathVariable Long id,
            @Valid @RequestBody FollowUpPracticeRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            HttpServletRequest httpRequest) {
        AiTaskStatusResponse task = service.createFollowUp(id, request.weakness(),
                currentUserId(httpRequest), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(httpRequest)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InterviewStateResponse> getState(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.getState(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<InterviewStateResponse> answer(@PathVariable Long id,
                                                       @Valid @RequestBody AnswerInterviewRequest request,
                                                       @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
                                                       HttpServletRequest httpRequest) {
        if (service.getState(id, currentUserId(httpRequest)).getExecutionMode() == com.intelligentresume.interview.domain.ExecutionMode.RULE) {
            return ApiResponse.success(service.ruleAnswer(id, request.getAnswer().trim(), currentUserId(httpRequest), idempotencyKey), traceId(httpRequest));
        }
        return ApiResponse.success(service.answer(id, request.getAnswer().trim(), currentUserId(httpRequest), idempotencyKey), traceId(httpRequest));
    }

    @PostMapping("/{id}/ai/retry")
    public ApiResponse<InterviewStateResponse> retry(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.retryAi(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/continue-with-rules")
    public ApiResponse<InterviewStateResponse> continueWithRules(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.continueWithRules(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<InterviewStateResponse> finish(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.finish(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReportResponse> report(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.report(id, currentUserId(httpRequest)), traceId(httpRequest));
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
