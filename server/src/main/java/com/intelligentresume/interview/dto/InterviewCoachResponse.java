package com.intelligentresume.interview.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * AI 面试教练响应 DTO。
 * 服务端按五维重新求和，不接受模型单独提供的总分。
 *
 * <p>【小白导读】一句话：模型必须按这里定义的格式「交卷」——出题时填 initialQuestion，
 * 点评时填 answerEvaluation；字段上的长度/条数/分数区间注解就是验收标准，
 * 不合格直接打回（契约修复或判失败）。
 *
 * <p>要点：
 * <ul>
 *   <li>首题与评估是同一个响应的两个可空分支，按调用场景只有一个非空。</li>
 *   <li>字段上的 {@code @NotBlank/@Size/@Min/@Max} 就是与模型的契约；反序列化时还开启了
 *       FAIL_ON_UNKNOWN_PROPERTIES，多字段也会失败。</li>
 *   <li>五维区间 0-25 / 0-25 / 0-20 / 0-20 / 0-10，合计上限 100；改区间要同改提示词。</li>
 *   <li>{@code informationComplete} 与 {@code completionReason/nextQuestion} 的互斥关系
 *       由服务层额外校验，注解表达不了。</li>
 * </ul>
 */
public class InterviewCoachResponse {

    /** 首题生成响应 */
    private InitialQuestion initialQuestion;

    /** 回答评估响应 */
    private AnswerEvaluation answerEvaluation;

    public InitialQuestion getInitialQuestion() { return initialQuestion; }
    public void setInitialQuestion(InitialQuestion initialQuestion) { this.initialQuestion = initialQuestion; }
    public AnswerEvaluation getAnswerEvaluation() { return answerEvaluation; }
    public void setAnswerEvaluation(AnswerEvaluation answerEvaluation) { this.answerEvaluation = answerEvaluation; }

    // ---- 首题 ----

    public static class InitialQuestion {
        @NotBlank
        @Size(min = 10, max = 500)
        private String question;

        @NotBlank
        @Size(min = 1, max = 100)
        private String focus;

        @NotNull
        @Size(min = 1, max = 5)
        private List<@Size(max = 300) String> expectedSignals;

        @NotNull
        @Size(min = 1, max = 3)
        private List<@Size(max = 50) String> coverageTags;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getFocus() { return focus; }
        public void setFocus(String focus) { this.focus = focus; }
        public List<String> getExpectedSignals() { return expectedSignals; }
        public void setExpectedSignals(List<String> expectedSignals) { this.expectedSignals = expectedSignals; }
        public List<String> getCoverageTags() { return coverageTags; }
        public void setCoverageTags(List<String> coverageTags) { this.coverageTags = coverageTags; }
    }

    // ---- 回答评估 ----

    public static class AnswerEvaluation {
        @Valid
        @NotNull
        private DimensionScores dimensionScores;

        @NotNull
        @Size(max = 3)
        private List<@Size(max = 500) String> strengths;

        @NotNull
        @Size(min = 1, max = 3)
        private List<@Size(max = 500) String> improvements;

        @Size(max = 5)
        private List<@Size(max = 200) String> evidenceQuotes;

        @NotBlank
        @Size(min = 50, max = 2000)
        private String suggestedAnswer;

        @Size(max = 3)
        private List<@Size(max = 500) String> resumeSuggestions;

        @Size(max = 3)
        private List<@Size(max = 500) String> expressionSuggestions;

        @NotNull
        @Size(min = 1, max = 5)
        private List<@Size(max = 50) String> coverageTags;

        private boolean informationComplete;
        private String completionReason;

        @Valid
        private NextQuestion nextQuestion;

        public DimensionScores getDimensionScores() { return dimensionScores; }
        public void setDimensionScores(DimensionScores dimensionScores) { this.dimensionScores = dimensionScores; }
        public List<String> getStrengths() { return strengths; }
        public void setStrengths(List<String> strengths) { this.strengths = strengths; }
        public List<String> getImprovements() { return improvements; }
        public void setImprovements(List<String> improvements) { this.improvements = improvements; }
        public List<String> getEvidenceQuotes() { return evidenceQuotes; }
        public void setEvidenceQuotes(List<String> evidenceQuotes) { this.evidenceQuotes = evidenceQuotes; }
        public String getSuggestedAnswer() { return suggestedAnswer; }
        public void setSuggestedAnswer(String suggestedAnswer) { this.suggestedAnswer = suggestedAnswer; }
        public List<String> getResumeSuggestions() { return resumeSuggestions; }
        public void setResumeSuggestions(List<String> resumeSuggestions) { this.resumeSuggestions = resumeSuggestions; }
        public List<String> getExpressionSuggestions() { return expressionSuggestions; }
        public void setExpressionSuggestions(List<String> expressionSuggestions) { this.expressionSuggestions = expressionSuggestions; }
        public List<String> getCoverageTags() { return coverageTags; }
        public void setCoverageTags(List<String> coverageTags) { this.coverageTags = coverageTags; }
        public boolean isInformationComplete() { return informationComplete; }
        public void setInformationComplete(boolean informationComplete) { this.informationComplete = informationComplete; }
        public String getCompletionReason() { return completionReason; }
        public void setCompletionReason(String completionReason) { this.completionReason = completionReason; }
        public NextQuestion getNextQuestion() { return nextQuestion; }
        public void setNextQuestion(NextQuestion nextQuestion) { this.nextQuestion = nextQuestion; }
    }

    // ---- 维度分数 ----

    public static class DimensionScores {
        @NotNull @Min(0) @Max(25)
        private Integer relevance;

        @NotNull @Min(0) @Max(25)
        private Integer evidenceSpecificity;

        @NotNull @Min(0) @Max(20)
        private Integer structureClarity;

        @NotNull @Min(0) @Max(20)
        private Integer roleCompetency;

        @NotNull @Min(0) @Max(10)
        private Integer authenticityReflection;

        public Integer getRelevance() { return relevance; }
        public void setRelevance(Integer relevance) { this.relevance = relevance; }
        public Integer getEvidenceSpecificity() { return evidenceSpecificity; }
        public void setEvidenceSpecificity(Integer evidenceSpecificity) { this.evidenceSpecificity = evidenceSpecificity; }
        public Integer getStructureClarity() { return structureClarity; }
        public void setStructureClarity(Integer structureClarity) { this.structureClarity = structureClarity; }
        public Integer getRoleCompetency() { return roleCompetency; }
        public void setRoleCompetency(Integer roleCompetency) { this.roleCompetency = roleCompetency; }
        public Integer getAuthenticityReflection() { return authenticityReflection; }
        public void setAuthenticityReflection(Integer authenticityReflection) { this.authenticityReflection = authenticityReflection; }

        public int total() {
            return relevance + evidenceSpecificity + structureClarity + roleCompetency + authenticityReflection;
        }
    }

    // ---- 下一题 ----

    public static class NextQuestion {
        @NotBlank
        @Size(min = 10, max = 500)
        private String question;

        @NotBlank
        @Size(min = 1, max = 100)
        private String focus;

        @NotNull
        @Size(min = 1, max = 5)
        private List<@Size(max = 300) String> expectedSignals;

        @NotNull
        @Size(min = 1, max = 3)
        private List<@Size(max = 50) String> coverageTags;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getFocus() { return focus; }
        public void setFocus(String focus) { this.focus = focus; }
        public List<String> getExpectedSignals() { return expectedSignals; }
        public void setExpectedSignals(List<String> expectedSignals) { this.expectedSignals = expectedSignals; }
        public List<String> getCoverageTags() { return coverageTags; }
        public void setCoverageTags(List<String> coverageTags) { this.coverageTags = coverageTags; }
    }
}
