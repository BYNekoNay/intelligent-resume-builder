package com.intelligentresume.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建 PDF 导出请求。
 *
 * <p>{@code templateCode} 的正则与 {@code ResumeTemplateCodes.SUPPORTED} 是同一份 7 个模板；
 * {@code @Pattern} 对 null 放行，所以必须配 {@code @NotNull}——否则 null 能通过 Bean Validation，
 * 服务层 {@code Set.contains(null)} 会抛 NPE（用户看到 500）。
 */
public record CreateExportRequest(
        @NotNull Long resumeVersionId,
        @NotNull @Pattern(regexp = "classic|modern|minimal|ats|executive|compact|academic", message = "模板代码不受支持") String templateCode
) {
}
