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
        // 两个检查回答不同的问题：
        //   ai-provider    —— 提供者是否已配置（密钥是否就位）
        //   ai-model-chain —— 现在是否真的有可调度的模型（额度未耗尽、未处于冷却期）
        // 只查前者会漏掉「密钥有效但所有模型额度耗尽」——那正是曾经把 AI 全线故障掩盖成 UP 的原因。
        String providerStatus = providerRegistry.hasAvailableProvider() ? "UP" : "DOWN";
        int availableModels = providerRegistry.availableModelCount();
        String chainStatus = availableModels > 0 ? "UP" : "DOWN";
        String pdfStatus = pdfServiceClient.checkHealth() ? "UP" : "DOWN";
        List<SystemHealthResponse.CapabilityStatus> checks = List.of(
                new SystemHealthResponse.CapabilityStatus("api", "UP"),
                new SystemHealthResponse.CapabilityStatus("ai-provider", providerStatus),
                new SystemHealthResponse.CapabilityStatus("ai-model-chain", chainStatus),
                new SystemHealthResponse.CapabilityStatus("pdf-renderer", pdfStatus));
        String overallStatus = "UP".equals(providerStatus) && "UP".equals(chainStatus)
                && "UP".equals(pdfStatus) ? "UP" : "DEGRADED";
        SystemHealthResponse payload = new SystemHealthResponse(
                "intelligent-resume-server", overallStatus, "0.1.0",
                SystemCapabilityRegistry.codes(), checks);
        return ApiResponse.success(payload, traceId);
    }
}
