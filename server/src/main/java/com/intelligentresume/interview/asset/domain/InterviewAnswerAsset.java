package com.intelligentresume.interview.asset.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "interview_answer_asset")
// ⚠ 必须在实体类上重复声明：@SQLRestriction 标在 @MappedSuperclass（SoftDeletableEntity）
// 上**不会被实体继承**（与 Resume / JobDescription 同款）。
@SQLRestriction("deleted_at IS NULL")
/**
 * 面试回答资产。软删除（V40 起）：{@code extends SoftDeletableEntity} 全局过滤已删行；
 * 清扫作业在恢复期结束后按引用分档：无 section 引用者物理删除，有引用者转最小快照
 * （正文常量化、反馈 JSON 清空），保证 {@code interview_asset_section.asset_id} 外键恒成立。
 */
public class InterviewAnswerAsset extends SoftDeletableEntity {
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
