package com.intelligentresume.scoring.dto;

import java.util.List;

/**
 * 评分解释。
 *
 * @param matched        完全匹配（归一化后直接命中）
 * @param partialMatched 同义词命中
 * @param missing        JD 中存在但简历中无
 * @param suggestions    改进建议
 * @param disclaimer     必须等于 app.scoring.user-disclaimer
 */
public record Explanation(
        List<String> matched,
        List<String> partialMatched,
        List<String> missing,
        List<String> suggestions,
        String disclaimer
) {}
