package com.intelligentresume.ats.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ats")
public class AtsController {

    @PostMapping("/check")
    public ApiResponse<Map<String, Object>> check(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        return ApiResponse.success(Map.of(
            "checkId", 1, "score", 85,
            "structure", List.of(),
            "keywords", Map.of(),
            "suggestions", List.of()
        ), traceId(httpRequest));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
