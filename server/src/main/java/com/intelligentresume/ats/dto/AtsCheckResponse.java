package com.intelligentresume.ats.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
