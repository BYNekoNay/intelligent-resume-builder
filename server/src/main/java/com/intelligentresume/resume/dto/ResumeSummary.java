package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;

/**
 * 简历列表摘要：只够画列表卡片。
 *
 * <p>与 {@link ResumeDetail} 的区别是少一个 {@code createdAt}，且列表按 {@code updatedAt} 倒序排列。
 */
public record ResumeSummary(
        Long id,
        String title,
        Long currentVersionId,
        Long jobDescriptionId,
        LocalDateTime updatedAt
) {}
