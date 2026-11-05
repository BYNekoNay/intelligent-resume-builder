package com.intelligentresume.scoring.dto;

import java.math.BigDecimal;

/**
 * 评分响应。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code matchResultId} 是结果复用的句柄：相同输入再次评分时返回的可能是同一条历史记录，前端不要假设每次 ID 都变。</li>
 *   <li>{@code explanation} 与分数同源（都来自同一次评估），不要在前端用分数反推命中项。</li>
 *   <li>{@code ruleVersion} 用于判断结果是否过期：版本升级后旧结果不会被当成新算法产物。</li>
 * </ul>
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
