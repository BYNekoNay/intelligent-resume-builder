package com.intelligentresume.system.dto;

/**
 * 匿名健康探针的精简响应：只暴露服务名与聚合状态。
 * 版本号、能力清单、各检查项明细等运营敏感信息收敛到认证端点 /api/system/health/detail。
 */
public record SystemHealthSummaryResponse(String service, String status) {
}
