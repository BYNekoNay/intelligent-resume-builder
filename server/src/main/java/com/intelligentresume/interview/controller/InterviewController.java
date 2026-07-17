package com.intelligentresume.interview.controller;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.dto.InterviewAnswerRequest;
import com.intelligentresume.interview.dto.InterviewResponses;
import com.intelligentresume.interview.dto.InterviewStartRequest;
import com.intelligentresume.interview.service.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) { this.service = service; }

    @PostMapping("/start")
    public ApiResponse<InterviewResponses.Start> start(@Valid @RequestBody InterviewStartRequest request,
                                                       HttpServletRequest httpRequest) {
        return ApiResponse.success(service.start(request, userId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<InterviewResponses.Answer> answer(@PathVariable Long id,
                                                         @Valid @RequestBody InterviewAnswerRequest request,
                                                         HttpServletRequest httpRequest) {
        return ApiResponse.success(service.answer(id, request, userId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<InterviewResponses.Report> report(@PathVariable Long id,
                                                         HttpServletRequest httpRequest) {
        return ApiResponse.success(service.report(id, userId(httpRequest)), traceId(httpRequest));
    }

    private Long userId(HttpServletRequest request) {
        Object value = request.getAttribute("currentUserId");
        if (value == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) value;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
