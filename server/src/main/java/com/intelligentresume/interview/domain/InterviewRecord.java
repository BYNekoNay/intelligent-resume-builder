package com.intelligentresume.interview.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "interview_record")
public class InterviewRecord extends BaseEntity {
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "round_no", nullable = false) private Integer roundNo;
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT") private String questionText;
    @Column(name = "answer_text", nullable = false, columnDefinition = "MEDIUMTEXT") private String answerText;
    @Column(name = "round_score", nullable = false) private Integer roundScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "feedback_json", nullable = false, columnDefinition = "json") private Map<String, Object> feedbackJson;

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
}
