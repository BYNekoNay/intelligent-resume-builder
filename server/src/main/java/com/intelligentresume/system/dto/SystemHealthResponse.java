package com.intelligentresume.system.dto;

import java.util.List;

/**
 * 认证健康明细的响应体：服务名、整体状态（UP/DEGRADED）、版本、能力码与逐项检查。
 *
 * <p>{@code status} 只有 UP/DEGRADED 两值，任一检查项 DOWN 即 DEGRADED（不做三态）；
 * {@code version} 目前是代码内硬编码，属于展示字段，不要用它做版本判断。
 */
public record SystemHealthResponse(String service, String status, String version,
                                   List<String> capabilities, List<CapabilityStatus> checks) {

    /** 单个能力项：{@code capability} 为固定名称，{@code status} 为 UP/DOWN。 */
    public record CapabilityStatus(String capability, String status) {
    }
}
