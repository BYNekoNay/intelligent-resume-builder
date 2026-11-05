package com.intelligentresume.export.dto;

import java.time.LocalDateTime;

/**
 * 导出任务状态响应。downloadUrl 为相对路径,由前端拼接 baseURL。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code downloadUrl} 只在 SUCCESS 时非空，指向 {@code /api/exports/files/{taskId}}，不含 storageKey。</li>
 *     <li>{@code errorMessage} 已经过 {@code PublicFailureCopy} 归一，不是数据库里的上游原文。</li>
 *     <li>{@code status} 取的是枚举 {@code name()}（PENDING/RUNNING/SUCCESS/FAILED/EXPIRED），前端按字符串比对。</li>
 * </ul>
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
