package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StartInterviewRequest(@NotNull InterviewSourceType sourceType, Long resumeVersionId,
                                    @Size(max = 100000) String externalResumeText,
                                    @NotNull Long jobDescriptionId, @NotNull InterviewMode interviewMode) {}
