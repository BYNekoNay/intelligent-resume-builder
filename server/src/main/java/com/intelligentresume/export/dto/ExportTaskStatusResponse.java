package com.intelligentresume.export.dto;

import java.time.LocalDateTime;

/**
 * 导出任务状态响应。downloadUrl 为相对路径,由前端拼接 baseURL。
 */
public record ExportTaskStatusResponse(
        Long taskId,
        String status,
        String templateCode,
        Long fileSizeBytes,
        String checksumSha256,
        LocalDateTime expiresAt,
        String errorMessage,
        String downloadUrl
) {
}
