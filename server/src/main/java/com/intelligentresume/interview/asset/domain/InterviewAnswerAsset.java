package com.intelligentresume.interview.asset.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "interview_answer_asset")
public class InterviewAnswerAsset extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "interview_record_id") private Long interviewRecordId;
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT") private String questionText;
    @Column(name = "original_answer_text", nullable = false, columnDefinition = "TEXT") private String originalAnswerText;
    @Column(name = "suggested_answer_text", columnDefinition = "TEXT") private String suggestedAnswerText;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "feedback_json", columnDefinition = "json") private Map<String, Object> feedbackJson;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getInterviewRecordId() { return interviewRecordId; }
    public void setInterviewRecordId(Long interviewRecordId) { this.interviewRecordId = interviewRecordId; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getOriginalAnswerText() { return originalAnswerText; }
    public void setOriginalAnswerText(String originalAnswerText) { this.originalAnswerText = originalAnswerText; }
    public String getSuggestedAnswerText() { return suggestedAnswerText; }
    public void setSuggestedAnswerText(String suggestedAnswerText) { this.suggestedAnswerText = suggestedAnswerText; }
    public Map<String, Object> getFeedbackJson() { return feedbackJson; }
    public void setFeedbackJson(Map<String, Object> feedbackJson) { this.feedbackJson = feedbackJson; }
}
