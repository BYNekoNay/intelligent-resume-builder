package com.intelligentresume.interview.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

/**
 * 一轮「题目 + 回答 + 评估」的落库记录，也是会话进度的唯一事实来源。
 *
 * <p>【小白导读】一句话：面试过掉一题就写一行这里——问了什么、答了什么、得了几分、
 * 点评详情（feedbackJson）、分数来自 AI 还是规则。报告页和历史列表全是读它聚合出来的。
 *
 * <p>要点：
 * <ul>
 *   <li>一轮一行，{@code (session_id, round_no)} 有唯一约束；轮次号 = 落库前的记录数 + 1。</li>
 *   <li>{@code feedbackJson} 的 key 是跨模块契约（分数字段、strengths/improvements、
 *       suggestedAnswer、coverageTags 等），报告、状态接口、资产导出都按 key 读取。</li>
 *   <li>{@code roundScore} 是五维之和（0-100），不是模型单独给的总分。</li>
 *   <li>{@code aiAttemptId} 指回生成这条记录的 attempt，便于追溯来源与排查。</li>
 *   <li>记录一旦写入即视为该轮完成；状态接口的「已完成轮数」直接计数本表。</li>
 * </ul>
 */
@Entity
@Table(name = "interview_record")
public class InterviewRecord extends BaseEntity {
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "round_no", nullable = false) private Integer roundNo;
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT") private String questionText;
    @Column(name = "answer_text", nullable = false, columnDefinition = "MEDIUMTEXT") private String answerText;
    @Column(name = "round_score", nullable = false) private Integer roundScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "feedback_json", nullable = false, columnDefinition = "json") private Map<String, Object> feedbackJson;

    @Enumerated(EnumType.STRING) @Column(name = "evaluation_source", length = 16) private EvaluationSource evaluationSource;
    @Column(name = "ai_attempt_id") private Long aiAttemptId;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public Integer getRoundScore() { return roundScore; }
    public void setRoundScore(Integer roundScore) { this.roundScore = roundScore; }
    public Map<String, Object> getFeedbackJson() { return feedbackJson; }
    public void setFeedbackJson(Map<String, Object> feedbackJson) { this.feedbackJson = feedbackJson; }

    public EvaluationSource getEvaluationSource() { return evaluationSource; }
    public void setEvaluationSource(EvaluationSource evaluationSource) { this.evaluationSource = evaluationSource; }
    public Long getAiAttemptId() { return aiAttemptId; }
    public void setAiAttemptId(Long aiAttemptId) { this.aiAttemptId = aiAttemptId; }
}
