package com.intelligentresume.application.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "application_record")
public class ApplicationRecord extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "job_description_id", nullable = false) private Long jobDescriptionId;
    @Column(name = "resume_version_id", nullable = false) private Long resumeVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32) private Status status;
    @Lob @Column(name = "cover_letter_text") private String coverLetterText;
    @Lob @Column(name = "opening_message_text") private String openingMessageText;
    @Lob @Column(name = "feedback_text") private String feedbackText;
    @Column(name = "applied_at") private LocalDateTime appliedAt;

    public enum Status { DRAFT, APPLIED, INTERVIEWING, OFFERED, REJECTED, WITHDRAWN }
    public Long getUserId() { return userId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public Status getStatus() { return status; }
    public String getCoverLetterText() { return coverLetterText; }
    public String getOpeningMessageText() { return openingMessageText; }
    public String getFeedbackText() { return feedbackText; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setUserId(Long value) { userId = value; }
    public void setJobDescriptionId(Long value) { jobDescriptionId = value; }
    public void setResumeVersionId(Long value) { resumeVersionId = value; }
    public void setStatus(Status value) { status = value; }
    public void setCoverLetterText(String value) { coverLetterText = value; }
    public void setOpeningMessageText(String value) { openingMessageText = value; }
    public void setFeedbackText(String value) { feedbackText = value; }
    public void setAppliedAt(LocalDateTime value) { appliedAt = value; }
}
