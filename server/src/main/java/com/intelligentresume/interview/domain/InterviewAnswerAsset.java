package com.intelligentresume.interview.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "interview_answer_asset")
public class InterviewAnswerAsset extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "interview_record_id") private Long interviewRecordId;
    @Lob @Column(name = "question_text", nullable = false) private String questionText;
    @Lob @Column(name = "original_answer_text", nullable = false) private String originalAnswerText;
    @Lob @Column(name = "suggested_answer_text") private String suggestedAnswerText;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "feedback_json", columnDefinition = "json") private Map<String, Object> feedbackJson;
    public Long getIdValue() { return getId(); }
    public Long getUserId() { return userId; }
    public Long getInterviewRecordId() { return interviewRecordId; }
    public String getQuestionText() { return questionText; }
    public String getOriginalAnswerText() { return originalAnswerText; }
    public String getSuggestedAnswerText() { return suggestedAnswerText; }
    public Map<String, Object> getFeedbackJson() { return feedbackJson; }
    public void setUserId(Long value) { userId = value; }
    public void setInterviewRecordId(Long value) { interviewRecordId = value; }
    public void setQuestionText(String value) { questionText = value; }
    public void setOriginalAnswerText(String value) { originalAnswerText = value; }
    public void setSuggestedAnswerText(String value) { suggestedAnswerText = value; }
    public void setFeedbackJson(Map<String, Object> value) { feedbackJson = value; }
}
