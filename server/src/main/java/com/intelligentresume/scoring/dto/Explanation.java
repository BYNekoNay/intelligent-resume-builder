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
 *
 * <p>要点：三个列表都是<b>JD 关键词的原样文本</b>（不是归一化后的 token），直接可以给用户看；
 * {@code suggestions} 由 {@code ScoringService} 按 missing 生成，可能为空场景下的固定鼓励语，不要当成错误信息。
 */
public record Explanation(
        List<String> matched,
        List<String> partialMatched,
        List<String> missing,
        List<String> suggestions,
        String disclaimer
) {}
