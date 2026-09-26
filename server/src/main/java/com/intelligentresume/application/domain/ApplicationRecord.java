package com.intelligentresume.application.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "application_record")
public class ApplicationRecord extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;
    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status;
    @Column(name = "cover_letter_text", columnDefinition = "TEXT")
    private String coverLetterText;
    @Column(name = "email_body_text", columnDefinition = "TEXT")
    private String emailBodyText;
    @Column(name = "opening_message_text", columnDefinition = "TEXT")
    private String openingMessageText;
    @Column(name = "feedback_text", columnDefinition = "TEXT")
    private String feedbackText;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
    @Column(name = "next_follow_up_at")
    private LocalDateTime nextFollowUpAt;
    /**
     * 当前状态进入时刻（仅在状态实际发生迁移时刷新，见 ApplicationService）。
     *
     * <p>修复模块核实报告 P1-4：投递看板 avgStageDurationDays.interviewing 原用
     * updatedAt 充当"进入面试时间"，但 updatedAt 在任何字段更新（改 follow-up、
     * 补 feedback、PUT 编辑）都会刷新，导致面试停留时长被静默重置。改用本字段，
     * status 未变化的更新不得刷新。
     */
    @Column(name = "stage_entered_at")
    private LocalDateTime stageEnteredAt;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public String getCoverLetterText() { return coverLetterText; }
    public void setCoverLetterText(String coverLetterText) { this.coverLetterText = coverLetterText; }
    public String getEmailBodyText() { return emailBodyText; }
    public void setEmailBodyText(String emailBodyText) { this.emailBodyText = emailBodyText; }
    public String getOpeningMessageText() { return openingMessageText; }
    public void setOpeningMessageText(String openingMessageText) { this.openingMessageText = openingMessageText; }
    public String getFeedbackText() { return feedbackText; }
    public void setFeedbackText(String feedbackText) { this.feedbackText = feedbackText; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public LocalDateTime getNextFollowUpAt() { return nextFollowUpAt; }
    public void setNextFollowUpAt(LocalDateTime nextFollowUpAt) { this.nextFollowUpAt = nextFollowUpAt; }
    public LocalDateTime getStageEnteredAt() { return stageEnteredAt; }
    public void setStageEnteredAt(LocalDateTime stageEnteredAt) { this.stageEnteredAt = stageEnteredAt; }
    public Long getVersion() { return version; }
}
