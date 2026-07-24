package com.intelligentresume.interview.asset.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interview-answer-assets")
public class InterviewAssetController {

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(new ArrayList<>(), traceId(httpRequest));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of("id", 1), traceId(httpRequest));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of("id", id), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(null, traceId(httpRequest));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
