package com.intelligentresume.interview.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;

/**
 * 一场模拟面试的会话（一对一，无子集合映射）。
 *
 * <p>要点：
 * <ul>
 *   <li>「已完成轮数」不在本表，而是 {@code interview_record} 的行数；这里只有题数区间。</li>
 *   <li>{@code min/maxQuestionCount} 由 {@code targetQuestionCount} 派生（分别约 50% / 150%），
 *       开场时统一写入；只改 target 不重算 min/max 会让完成判定失真。</li>
 *   <li>{@code currentQuestion} 为空通常意味着「正在生成」或「已结束」，不能单独用来判断状态。</li>
 *   <li>{@code sourceType} 决定 {@code resumeVersionId} 与 {@code externalResumeText} 哪个有值，二者互斥。</li>
 *   <li>凡是「读 → 判断 → 写」的流程都必须通过 {@code findByIdAndUserIdForUpdate} 加行锁。</li>
 * </ul>
 */
@Entity
@Table(name = "interview_session")
public class InterviewSession extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private InterviewSourceType sourceType;
    @Column(name = "resume_version_id") private Long resumeVersionId;
    @Column(name = "external_resume_text", columnDefinition = "MEDIUMTEXT") private String externalResumeText;
    @Column(name = "job_description_id") private Long jobDescriptionId;
    @Enumerated(EnumType.STRING) @Column(name = "interview_mode", nullable = false, length = 32) private InterviewMode interviewMode;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32) private InterviewStatus status;
    @Column(name = "current_question", columnDefinition = "TEXT") private String currentQuestion;
    @Enumerated(EnumType.STRING) @Column(name = "output_language", nullable = false, length = 16)
    private InterviewOutputLanguage outputLanguage = InterviewOutputLanguage.ZH_CN;

    @Column(name = "target_question_count", nullable = false) private Integer targetQuestionCount = 6;
    @Column(name = "min_question_count", nullable = false) private Integer minQuestionCount = 3;
    @Column(name = "max_question_count", nullable = false) private Integer maxQuestionCount = 9;
    @Enumerated(EnumType.STRING) @Column(name = "execution_mode", length = 16) private ExecutionMode executionMode;
    @Enumerated(EnumType.STRING) @Column(name = "completion_reason", length = 64) private CompletionReason completionReason;

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
    public InterviewOutputLanguage getOutputLanguage() { return outputLanguage; }
    public void setOutputLanguage(InterviewOutputLanguage outputLanguage) { this.outputLanguage = outputLanguage; }

    public Integer getTargetQuestionCount() { return targetQuestionCount; }
    public void setTargetQuestionCount(Integer targetQuestionCount) { this.targetQuestionCount = targetQuestionCount; }
    public Integer getMinQuestionCount() { return minQuestionCount; }
    public void setMinQuestionCount(Integer minQuestionCount) { this.minQuestionCount = minQuestionCount; }
    public Integer getMaxQuestionCount() { return maxQuestionCount; }
    public void setMaxQuestionCount(Integer maxQuestionCount) { this.maxQuestionCount = maxQuestionCount; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public void setExecutionMode(ExecutionMode executionMode) { this.executionMode = executionMode; }
    public CompletionReason getCompletionReason() { return completionReason; }
    public void setCompletionReason(CompletionReason completionReason) { this.completionReason = completionReason; }
}
