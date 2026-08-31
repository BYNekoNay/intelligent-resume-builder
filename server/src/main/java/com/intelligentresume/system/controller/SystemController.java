package com.intelligentresume.system.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.system.dto.SystemHealthResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/health")
    public ApiResponse<SystemHealthResponse> health(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        SystemHealthResponse payload = new SystemHealthResponse(
                "intelligent-resume-server", "UP", "0.1.0",
                java.util.List.of("resume", "ai-tasks", "ats", "applications", "communications", "interviews", "imports", "pdf-export"));
        return ApiResponse.success(payload, traceId);
    }
}
