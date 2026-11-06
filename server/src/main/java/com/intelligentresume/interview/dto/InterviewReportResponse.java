package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;

import java.util.List;

/**
 * 复盘报告：总分 + 五维均分 + 跨轮去重的亮点/短板/建议 + 逐轮明细。
 *
 * <p>【小白导读】一句话：面试结束后报告页要展示的全部内容，由服务端从已有记录
 * 聚合而来，不含任何实时 AI 调用。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code totalScore} 与五维都是「各轮平均」后四舍五入，不是求和。</li>
 *   <li>列表型字段（strengths/weaknesses/resumeSuggestions/expressionSuggestions）已跨轮去重并截断，
 *       条数不保证等于轮数。</li>
 *   <li>{@code aiEvaluatedRounds + ruleEvaluatedRounds} 应等于 {@code actualQuestionCount}，
 *       前端据此展示评估来源的可信度。</li>
 *   <li>{@code rounds} 只由已有记录组装，不触发新的 AI 调用。</li>
 * </ul>
 */
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
            Long recordId,
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
