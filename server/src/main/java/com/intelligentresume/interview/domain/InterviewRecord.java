package com.intelligentresume.interview.domain;
import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
@Entity @Table(name="interview_record")
public class InterviewRecord extends BaseEntity {
    @Column(name="session_id",nullable=false) private Long sessionId;
    @Lob @Column(name="question_text",nullable=false) private String questionText;
    @Lob @Column(name="answer_text",nullable=false) private String answerText;
    @Column(name="round_score",nullable=false) private Integer roundScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="feedback_json",nullable=false,columnDefinition="json") private Map<String,Object> feedbackJson;
    public Integer getRoundScore(){return roundScore;} public Map<String,Object> getFeedbackJson(){return feedbackJson;}
    public Long getSessionId(){return sessionId;}
    public void setSessionId(Long v){sessionId=v;} public void setQuestionText(String v){questionText=v;} public void setAnswerText(String v){answerText=v;} public void setRoundScore(Integer v){roundScore=v;} public void setFeedbackJson(Map<String,Object> v){feedbackJson=v;}
}
