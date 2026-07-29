package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewStatus;

/**
 * 面试会话状态响应。开始、回答、重试、规则降级统一返回此结构。
 */
public class InterviewStateResponse {

    private Long interviewId;
    private InterviewStatus status;
    private ExecutionMode executionMode;
    private String currentQuestion;
    private Integer currentQuestionNo;
    private int completedQuestionCount;
    private int targetQuestionCount;
    private int minQuestionCount;
    private int maxQuestionCount;
    private LastEvaluation lastEvaluation;
    private AiFailureInfo aiFailure;
    private CompletionReason completionReason;

    public InterviewStateResponse() {}

    public InterviewStateResponse(Long interviewId, InterviewStatus status, ExecutionMode executionMode,
                                  String currentQuestion, Integer currentQuestionNo,
                                  int completedQuestionCount, int targetQuestionCount,
                                  int minQuestionCount, int maxQuestionCount,
                                  LastEvaluation lastEvaluation, AiFailureInfo aiFailure,
                                  CompletionReason completionReason) {
        this.interviewId = interviewId;
        this.status = status;
        this.executionMode = executionMode;
        this.currentQuestion = currentQuestion;
        this.currentQuestionNo = currentQuestionNo;
        this.completedQuestionCount = completedQuestionCount;
        this.targetQuestionCount = targetQuestionCount;
        this.minQuestionCount = minQuestionCount;
        this.maxQuestionCount = maxQuestionCount;
        this.lastEvaluation = lastEvaluation;
        this.aiFailure = aiFailure;
        this.completionReason = completionReason;
    }

    public Long getInterviewId() { return interviewId; }
    public void setInterviewId(Long interviewId) { this.interviewId = interviewId; }
    public InterviewStatus getStatus() { return status; }
    public void setStatus(InterviewStatus status) { this.status = status; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public void setExecutionMode(ExecutionMode executionMode) { this.executionMode = executionMode; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public Integer getCurrentQuestionNo() { return currentQuestionNo; }
    public void setCurrentQuestionNo(Integer currentQuestionNo) { this.currentQuestionNo = currentQuestionNo; }
    public int getCompletedQuestionCount() { return completedQuestionCount; }
    public void setCompletedQuestionCount(int completedQuestionCount) { this.completedQuestionCount = completedQuestionCount; }
    public int getTargetQuestionCount() { return targetQuestionCount; }
    public void setTargetQuestionCount(int targetQuestionCount) { this.targetQuestionCount = targetQuestionCount; }
    public int getMinQuestionCount() { return minQuestionCount; }
    public void setMinQuestionCount(int minQuestionCount) { this.minQuestionCount = minQuestionCount; }
    public int getMaxQuestionCount() { return maxQuestionCount; }
    public void setMaxQuestionCount(int maxQuestionCount) { this.maxQuestionCount = maxQuestionCount; }
    public LastEvaluation getLastEvaluation() { return lastEvaluation; }
    public void setLastEvaluation(LastEvaluation lastEvaluation) { this.lastEvaluation = lastEvaluation; }
    public AiFailureInfo getAiFailure() { return aiFailure; }
    public void setAiFailure(AiFailureInfo aiFailure) { this.aiFailure = aiFailure; }
    public CompletionReason getCompletionReason() { return completionReason; }
    public void setCompletionReason(CompletionReason completionReason) { this.completionReason = completionReason; }

    // ---- 最后一轮评估摘要 ----

    public static class LastEvaluation {
        private Long recordId;
        private int roundNo;
        private String questionText;
        private String answerText;
        private int roundScore;
        private DimensionScores dimensionScores;
        private EvaluationSource evaluationSource;
        private java.util.List<String> strengths;
        private java.util.List<String> improvements;
        private String suggestedAnswer;

        public Long getRecordId() { return recordId; }
        public void setRecordId(Long recordId) { this.recordId = recordId; }
        public int getRoundNo() { return roundNo; }
        public void setRoundNo(int roundNo) { this.roundNo = roundNo; }
        public String getQuestionText() { return questionText; }
        public void setQuestionText(String questionText) { this.questionText = questionText; }
        public String getAnswerText() { return answerText; }
        public void setAnswerText(String answerText) { this.answerText = answerText; }
        public int getRoundScore() { return roundScore; }
        public void setRoundScore(int roundScore) { this.roundScore = roundScore; }
        public DimensionScores getDimensionScores() { return dimensionScores; }
        public void setDimensionScores(DimensionScores dimensionScores) { this.dimensionScores = dimensionScores; }
        public EvaluationSource getEvaluationSource() { return evaluationSource; }
        public void setEvaluationSource(EvaluationSource evaluationSource) { this.evaluationSource = evaluationSource; }
        public java.util.List<String> getStrengths() { return strengths; }
        public void setStrengths(java.util.List<String> strengths) { this.strengths = strengths; }
        public java.util.List<String> getImprovements() { return improvements; }
        public void setImprovements(java.util.List<String> improvements) { this.improvements = improvements; }
        public String getSuggestedAnswer() { return suggestedAnswer; }
        public void setSuggestedAnswer(String suggestedAnswer) { this.suggestedAnswer = suggestedAnswer; }
    }

    public static class DimensionScores {
        private int relevance;
        private int evidenceSpecificity;
        private int structureClarity;
        private int roleCompetency;
        private int authenticityReflection;

        public int getRelevance() { return relevance; }
        public void setRelevance(int relevance) { this.relevance = relevance; }
        public int getEvidenceSpecificity() { return evidenceSpecificity; }
        public void setEvidenceSpecificity(int evidenceSpecificity) { this.evidenceSpecificity = evidenceSpecificity; }
        public int getStructureClarity() { return structureClarity; }
        public void setStructureClarity(int structureClarity) { this.structureClarity = structureClarity; }
        public int getRoleCompetency() { return roleCompetency; }
        public void setRoleCompetency(int roleCompetency) { this.roleCompetency = roleCompetency; }
        public int getAuthenticityReflection() { return authenticityReflection; }
        public void setAuthenticityReflection(int authenticityReflection) { this.authenticityReflection = authenticityReflection; }
    }

    // ---- AI 失败信息 ----

    public static class AiFailureInfo {
        private Long operationId;
        private String stage; // INITIAL_QUESTION | ANSWER_EVALUATION
        private boolean retryable;
        private boolean reauthorizationRequired;
        private String messageCode;

        public Long getOperationId() { return operationId; }
        public void setOperationId(Long operationId) { this.operationId = operationId; }
        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public boolean isRetryable() { return retryable; }
        public void setRetryable(boolean retryable) { this.retryable = retryable; }
        public boolean isReauthorizationRequired() { return reauthorizationRequired; }
        public void setReauthorizationRequired(boolean reauthorizationRequired) { this.reauthorizationRequired = reauthorizationRequired; }
        public String getMessageCode() { return messageCode; }
        public void setMessageCode(String messageCode) { this.messageCode = messageCode; }
    }
}
