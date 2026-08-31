package com.intelligentresume.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建 PDF 导出请求。
 */
public record CreateExportRequest(
        @NotNull Long resumeVersionId,
        @Pattern(regexp = "classic|modern|minimal|ats|executive|compact|academic", message = "模板代码不受支持") String templateCode
) {
}
