package com.intelligentresume.jobdescription.dto;

import java.time.LocalDateTime;

/**
 * JD 列表项：列表页只渲染标题、公司、正文预览与解析时间。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code jdTextPreview} 是服务端把连续空白压成一个空格后截到 120 字符再加 "..."
 *       的结果，<b>不是</b>原文前缀的忠实副本，不要拿它做检索或回填；</li>
 *   <li>{@code parsedAt} 为 null 即"还没解析过"，前端据此显示解析入口/未解析状态；</li>
 *   <li>不含 {@code parsedKeywordsJson} 与全文，需要时单独查详情。</li>
 * </ul>
 */
public record JobDescriptionSummary(
        Long id, String title, String companyName, String jdTextPreview,
        LocalDateTime parsedAt, LocalDateTime updatedAt
) {}
