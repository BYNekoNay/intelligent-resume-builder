package com.intelligentresume.interview.dto;
import com.intelligentresume.interview.domain.InterviewSession;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record InterviewStartRequest(@NotNull InterviewSession.SourceType sourceType,Long resumeVersionId,@Size(max=50000) String externalResumeText,@NotNull Long jobDescriptionId,@NotNull InterviewSession.Mode interviewMode){}
