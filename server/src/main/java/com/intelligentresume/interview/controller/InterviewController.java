package com.intelligentresume.interview.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    @PostMapping("/start")
    public ApiResponse<Map<String, Object>> start(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of("interviewId", 1, "firstQuestion", "Tell me about yourself."), traceId(httpRequest));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<Map<String, Object>> answer(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of(
            "recordId", 1, "questionText", "Q", "roundScore", 80,
            "feedback", Map.of("strengths", java.util.List.of(), "improvements", java.util.List.of()),
            "nextQuestion", "Next question"
        ), traceId(httpRequest));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<Map<String, Object>> report(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of(
            "totalScore", 85, "summary", "Good interview.",
            "resumeSuggestions", java.util.List.of()
        ), traceId(httpRequest));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
