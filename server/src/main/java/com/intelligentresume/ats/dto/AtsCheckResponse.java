package com.intelligentresume.ats.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ATS 检查结果响应：规则结果 + 可选的 AI 结论 + 降级说明。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code analysisStatus} 是判断"AI 那部分现在什么状态"的唯一依据；{@code aiInsights} 只有
 *       COMPLETED 时非空，{@code fallback} 只有降级时非空。</li>
 *   <li>{@code resumeId} 在 {@code ANALYZING} 时为 null（服务端此时不回查简历版本），
 *       前端不要拿它做必填。</li>
 *   <li>{@code totalScore} 是规则总分（来自 {@code ScoringService}）；{@code checks} 里的各子项同样由规则算出，
 *       AI 不参与打分。</li>
 *   <li>{@code disclaimer} 是固定免责声明，读不到历史值时服务端会补上默认文案。</li>
 * </ul>
 */
public record AtsCheckResponse(
        Long id,
        Long resumeId,
        Long resumeVersionId,
        Long jobDescriptionId,
        BigDecimal totalScore,
        Map<String, Object> checks,
        List<String> passedChecks,
        List<String> risks,
        List<String> priorities,
        String disclaimer,
        AtsAnalysisStatus analysisStatus,
        AtsAnalysisSource analysisSource,
        Long aiTaskId,
        AtsAiInsights aiInsights,
        AtsFallbackInfo fallback,
        /** B4：关键词三档明细；旧记录为 null（前端隐藏面板，不等于"全部命中"）。 */
        KeywordGap keywordGap
) {}
