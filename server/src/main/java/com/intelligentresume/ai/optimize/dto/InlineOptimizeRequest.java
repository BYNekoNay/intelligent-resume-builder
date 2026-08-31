package com.intelligentresume.ai.optimize.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class InlineOptimizeRequest {

    @NotNull(message = "resumeVersionId is required")
    @Positive(message = "resumeVersionId must be positive")
    private Long resumeVersionId;

    @NotBlank(message = "section is required")
    @Size(max = 64, message = "section must not exceed 64 characters")
    private String section;

    @NotBlank(message = "content is required")
    @Size(max = 30000, message = "content must not exceed 30000 characters")
    private String content;

    @Size(max = 30000, message = "jdContext must not exceed 30000 characters")
    private String jdContext;

    @Positive(message = "jobDescriptionId must be positive")
    private Long jobDescriptionId;

    public InlineOptimizeRequest() {}

    public InlineOptimizeRequest(Long resumeVersionId, String section, String content, String jdContext) {
        this.resumeVersionId = resumeVersionId;
        this.section = section;
        this.content = content;
        this.jdContext = jdContext;
    }

    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getJdContext() { return jdContext; }
    public void setJdContext(String jdContext) { this.jdContext = jdContext; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
}
