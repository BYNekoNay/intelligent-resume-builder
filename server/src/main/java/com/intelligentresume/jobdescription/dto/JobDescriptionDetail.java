package com.intelligentresume.jobdescription.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * JD 详情响应：详情页与编辑器需要的全部字段。
 *
 * <p>要点：
 * <ul>
 *   <li>唯一携带完整 {@code jdText} 的响应；列表只给预览，引用只给标题/公司；</li>
 *   <li>{@code parsedKeywordsJson} 是原始落库结构（外层 version + data），没有二次加工，
 *       前端读关键词要取 {@code data.keywords}；未解析时为 null；</li>
 *   <li>{@code parsedAt}/{@code parsedVersion} 与 json 三者同源，正文被修改后会同时变 null；</li>
 *   <li>创建、更新、查询、解析四个接口共用本对象，字段增减即同时改四处契约。</li>
 * </ul>
 */
public record JobDescriptionDetail(
        Long id, String title, String companyName, String jdText,
        Map<String, Object> parsedKeywordsJson,
        LocalDateTime parsedAt, String parsedVersion,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {}
