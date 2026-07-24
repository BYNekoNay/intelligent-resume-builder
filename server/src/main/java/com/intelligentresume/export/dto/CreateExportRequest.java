package com.intelligentresume.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 创建 PDF 导出请求。templateCode 必须为 "classic"。
 */
public record CreateExportRequest(
        @NotNull Long resumeVersionId,
        @Pattern(regexp = "classic", message = "模板代码必须为 classic") String templateCode
) {
}
