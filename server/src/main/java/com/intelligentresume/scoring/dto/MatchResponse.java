package com.intelligentresume.scoring.dto;

import java.math.BigDecimal;

/**
 * 评分响应。
 */
public record MatchResponse(
        Long matchResultId,
        BigDecimal totalScore,
        BigDecimal keywordScore,
        BigDecimal skillScore,
        BigDecimal experienceScore,
        Explanation explanation,
        String ruleVersion
) {}
