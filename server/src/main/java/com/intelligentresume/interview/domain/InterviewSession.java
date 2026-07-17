package com.intelligentresume.interview.domain;
import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="interview_session")
public class InterviewSession extends BaseEntity {
    @Column(name="user_id",nullable=false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(name="source_type",nullable=false) private SourceType sourceType;
    @Column(name="resume_version_id") private Long resumeVersionId;
    @Lob @Column(name="external_resume_text") private String externalResumeText;
    @Column(name="job_description_id",nullable=false) private Long jobDescriptionId;
    @Enumerated(EnumType.STRING) @Column(name="interview_mode",nullable=false) private Mode interviewMode;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false) private Status status;
    @Lob @Column(name="current_question",nullable=false) private String currentQuestion;
    public enum SourceType { PLATFORM_RESUME, EXTERNAL_RESUME }
    public enum Mode { JD_TARGETED, TECHNICAL, BEHAVIORAL, COMPREHENSIVE }
    public enum Status { ACTIVE, COMPLETED }
    public Long getUserId(){return userId;} public SourceType getSourceType(){return sourceType;} public Long getResumeVersionId(){return resumeVersionId;} public String getExternalResumeText(){return externalResumeText;} public Long getJobDescriptionId(){return jobDescriptionId;} public Mode getInterviewMode(){return interviewMode;} public Status getStatus(){return status;} public String getCurrentQuestion(){return currentQuestion;}
    public void setUserId(Long v){userId=v;} public void setSourceType(SourceType v){sourceType=v;} public void setResumeVersionId(Long v){resumeVersionId=v;} public void setExternalResumeText(String v){externalResumeText=v;} public void setJobDescriptionId(Long v){jobDescriptionId=v;} public void setInterviewMode(Mode v){interviewMode=v;} public void setStatus(Status v){status=v;} public void setCurrentQuestion(String v){currentQuestion=v;}
}
