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
    public Long getVersion() { return version; }
}
