package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;

/**
 * 简历详情响应：主档元数据 + 当前版本指针。
 *
 * <p>刻意不含版本内容——内容体积可达 256KB，需要时单独调版本详情接口。
 * 未存过任何版本时 {@code currentVersionId} 为 null。
 */
public record ResumeDetail(
        Long id,
        String title,
        Long currentVersionId,
        Long jobDescriptionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
