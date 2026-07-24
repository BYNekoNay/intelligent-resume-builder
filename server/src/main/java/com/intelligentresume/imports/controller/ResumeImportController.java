package com.intelligentresume.imports.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/resume-imports")
public class ResumeImportController {

    @PostMapping("/parse")
    public ApiResponse<Map<String, Object>> parse(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of(
            "extractedText", "",
            "normalizedPreview", Map.of(),
            "suggestions", java.util.List.of()
        ), traceId(httpRequest));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
