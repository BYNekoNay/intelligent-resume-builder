package com.intelligentresume.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建 PDF 导出请求。
 *
 * <p>{@code templateCode} 的正则与 {@code ResumeTemplateCodes.SUPPORTED} 是同一份 7 个模板；
 * 但它没有 {@code @NotNull}——null 能通过 Bean Validation，服务层 {@code Set.contains(null)} 会抛 NPE。
 */
public record CreateExportRequest(
        @NotNull Long resumeVersionId,
        @Pattern(regexp = "classic|modern|minimal|ats|executive|compact|academic", message = "模板代码不受支持") String templateCode
) {
}
