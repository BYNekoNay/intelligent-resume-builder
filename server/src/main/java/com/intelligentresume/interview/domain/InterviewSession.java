package com.intelligentresume.interview.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "interview_session")
public class InterviewSession extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private InterviewSourceType sourceType;
    @Column(name = "resume_version_id") private Long resumeVersionId;
    @Column(name = "external_resume_text", columnDefinition = "MEDIUMTEXT") private String externalResumeText;
    @Column(name = "job_description_id", nullable = false) private Long jobDescriptionId;
    @Enumerated(EnumType.STRING) @Column(name = "interview_mode", nullable = false, length = 32) private InterviewMode interviewMode;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32) private InterviewStatus status;
    @Column(name = "current_question", nullable = false, columnDefinition = "TEXT") private String currentQuestion;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public InterviewSourceType getSourceType() { return sourceType; }
    public void setSourceType(InterviewSourceType sourceType) { this.sourceType = sourceType; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public String getExternalResumeText() { return externalResumeText; }
    public void setExternalResumeText(String externalResumeText) { this.externalResumeText = externalResumeText; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public InterviewMode getInterviewMode() { return interviewMode; }
    public void setInterviewMode(InterviewMode interviewMode) { this.interviewMode = interviewMode; }
    public InterviewStatus getStatus() { return status; }
    public void setStatus(InterviewStatus status) { this.status = status; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
}
