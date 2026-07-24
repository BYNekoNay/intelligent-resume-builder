package com.intelligentresume.jobdescription.dto;

import java.util.List;

/**
 * 确定性关键词解析结果。
 *
 * @param role         第一行非空短行(可选,>60 字符截断)
 * @param keywords     词典命中(去重,保持首次出现顺序)
 * @param requirements 经验年限、教育等提取结果
 */
public record ParsedKeywordsResponse(
        String role,
        List<String> keywords,
        List<String> requirements
) {}
