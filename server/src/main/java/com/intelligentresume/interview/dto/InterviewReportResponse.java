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
        Integer ruleEvaluatedRounds,
        List<RoundDetail> rounds
) {
    public record DimensionScores(
            int relevance,
            int evidenceSpecificity,
            int structureClarity,
            int roleCompetency,
            int authenticityReflection
    ) {}

    /** 逐轮问答明细（仅基于已有 interview_record 组装，不新增 AI 调用）。 */
    public record RoundDetail(
            Integer roundNo,
            String questionText,
            String answerText,
            Integer roundScore,
            DimensionScores dimensionScores,
            List<String> strengths,
            List<String> improvements,
            String suggestedAnswer,
            EvaluationSource evaluationSource
    ) {}
}
