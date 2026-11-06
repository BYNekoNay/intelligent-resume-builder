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

/**
 * 系统健康端点：匿名探针与认证明细两个口径。
 *
 * <p>【小白导览】①解决什么业务问题：回答「系统现在活着吗、哪些部件正常」——
 * 给 nginx/容器探活用（匿名 /health，只报 UP 或 DEGRADED），给运维排障用
 * （认证 /health/detail，报版本、能力清单和每个部件的逐项状态）。
 * ②在链路哪里：上游是监控探针与运维；下游是 AI 提供方注册表和 PDF 服务客户端
 * （要真去探测它们）。③改坏它用户看到什么：普通用户无感；但探针误报 DOWN →
 * 容器被反复重启；明细泄露（匿名口径带版本细节）→ 给攻击者递情报。
 *
 * <p>为什么分两个：
 * <ul>
 *   <li>{@code /health} 匿名可达，只给服务名与聚合状态——够 nginx/容器探活，不泄露版本与依赖拓扑；</li>
 *   <li>{@code /health/detail} 需要认证，返回版本、能力清单与逐项状态，供运维定位。</li>
 * </ul>
 *
 * <p>检查项刻意包含 {@code ai-provider}（密钥是否配置）与 {@code ai-model-chain}（当前是否真有可用模型）：
 * 只查前者会把「密钥有效但额度耗尽」掩盖成 UP，这是曾经的线上事故口径。
 */
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
                SERVICE_NAME, overallStatus(checks), "0.5.0",
                SystemCapabilityRegistry.codes(), checks);
        return ApiResponse.success(payload, traceId);
    }

    // 两个检查回答不同的问题：
    //   ai-provider    —— 提供者是否已配置（密钥是否就位）
    //   ai-model-chain —— 现在是否真的有可调度的模型（额度未耗尽、未处于冷却期）
    // 只查前者会漏掉「密钥有效但所有模型额度耗尽」——那正是曾经把 AI 全线故障掩盖成 UP 的原因。
    /**
     * 逐项探测：会发起真实的出站检查（模型链可用数、PDF 服务健康），因此本端点在限流表里
     * 是**唯一受限的 GET**；新增检查项时要评估是否也带来出站成本。
     */
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
