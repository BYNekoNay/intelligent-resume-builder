package com.intelligentresume.system.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.export.service.PdfServiceClient;
import com.intelligentresume.system.SystemCapabilityRegistry;
import com.intelligentresume.system.dto.SystemHealthResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AiProviderRegistry providerRegistry;
    private final PdfServiceClient pdfServiceClient;

    public SystemController(AiProviderRegistry providerRegistry, PdfServiceClient pdfServiceClient) {
        this.providerRegistry = providerRegistry;
        this.pdfServiceClient = pdfServiceClient;
    }

    @GetMapping("/health")
    public ApiResponse<SystemHealthResponse> health(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String aiStatus = providerRegistry.hasAvailableProvider() ? "UP" : "DOWN";
        String pdfStatus = pdfServiceClient.checkHealth() ? "UP" : "DOWN";
        List<SystemHealthResponse.CapabilityStatus> checks = List.of(
                new SystemHealthResponse.CapabilityStatus("api", "UP"),
                new SystemHealthResponse.CapabilityStatus("ai-provider", aiStatus),
                new SystemHealthResponse.CapabilityStatus("pdf-renderer", pdfStatus));
        String overallStatus = "UP".equals(aiStatus) && "UP".equals(pdfStatus) ? "UP" : "DEGRADED";
        SystemHealthResponse payload = new SystemHealthResponse(
                "intelligent-resume-server", overallStatus, "0.1.0",
                SystemCapabilityRegistry.codes(), checks);
        return ApiResponse.success(payload, traceId);
    }
}
