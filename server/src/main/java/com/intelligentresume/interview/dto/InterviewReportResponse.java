package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;

import java.util.List;

public record InterviewReportResponse(
        Integer totalScore,
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> resumeSuggestions,
        List<String> expressionSuggestions,
        DimensionScores dimensionScores,
        Integer targetQuestionCount,
        Integer actualQuestionCount,
        CompletionReason completionReason,
        EvaluationSource evaluationSource,
        Integer aiEvaluatedRounds,
        Integer ruleEvaluatedRounds
) {
    public record DimensionScores(
            int relevance,
            int evidenceSpecificity,
            int structureClarity,
            int roleCompetency,
            int authenticityReflection
    ) {}
}
