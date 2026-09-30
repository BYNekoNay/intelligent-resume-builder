package com.intelligentresume.system.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.export.service.PdfServiceClient;
import com.intelligentresume.system.SystemCapabilityRegistry;
import com.intelligentresume.system.dto.SystemHealthResponse;
import com.intelligentresume.system.dto.SystemHealthSummaryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final String SERVICE_NAME = "intelligent-resume-server";

    private final AiProviderRegistry providerRegistry;
    private final PdfServiceClient pdfServiceClient;

    public SystemController(AiProviderRegistry providerRegistry, PdfServiceClient pdfServiceClient) {
        this.providerRegistry = providerRegistry;
        this.pdfServiceClient = pdfServiceClient;
    }

    /**
     * 匿名存活探针：只返回服务名与聚合状态，不含版本、能力清单与检查项明细。
     * 完整健康明细见 {@link #healthDetail(HttpServletRequest)}（需认证）。
     */
    @GetMapping("/health")
    public ApiResponse<SystemHealthSummaryResponse> health(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ApiResponse.success(
                new SystemHealthSummaryResponse(SERVICE_NAME, overallStatus(checks())), traceId);
    }

    /**
     * 认证端点：完整健康明细（版本、能力清单、各检查项），供运维与监控使用。
     * 由 SecurityConfig 的 anyRequest().authenticated() 覆盖，无需显式放行规则。
     */
    @GetMapping("/health/detail")
    public ApiResponse<SystemHealthResponse> healthDetail(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        List<SystemHealthResponse.CapabilityStatus> checks = checks();
        SystemHealthResponse payload = new SystemHealthResponse(
                SERVICE_NAME, overallStatus(checks), "0.1.0",
                SystemCapabilityRegistry.codes(), checks);
        return ApiResponse.success(payload, traceId);
    }

    // 两个检查回答不同的问题：
    //   ai-provider    —— 提供者是否已配置（密钥是否就位）
    //   ai-model-chain —— 现在是否真的有可调度的模型（额度未耗尽、未处于冷却期）
    // 只查前者会漏掉「密钥有效但所有模型额度耗尽」——那正是曾经把 AI 全线故障掩盖成 UP 的原因。
    private List<SystemHealthResponse.CapabilityStatus> checks() {
        String providerStatus = providerRegistry.hasAvailableProvider() ? "UP" : "DOWN";
        int availableModels = providerRegistry.availableModelCount();
        String chainStatus = availableModels > 0 ? "UP" : "DOWN";
        String pdfStatus = pdfServiceClient.checkHealth() ? "UP" : "DOWN";
        return List.of(
                new SystemHealthResponse.CapabilityStatus("api", "UP"),
                new SystemHealthResponse.CapabilityStatus("ai-provider", providerStatus),
                new SystemHealthResponse.CapabilityStatus("ai-model-chain", chainStatus),
                new SystemHealthResponse.CapabilityStatus("pdf-renderer", pdfStatus));
    }

    /** 任一检查 DOWN 则整体 DEGRADED（api 恒为 UP，等价于逐项与判断）。 */
    private String overallStatus(List<SystemHealthResponse.CapabilityStatus> checks) {
        boolean allUp = checks.stream().allMatch(check -> "UP".equals(check.status()));
        return allUp ? "UP" : "DEGRADED";
    }
}
