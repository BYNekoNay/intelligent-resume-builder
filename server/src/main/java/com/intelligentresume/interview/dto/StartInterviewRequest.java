package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StartInterviewRequest(@NotNull InterviewSourceType sourceType, Long resumeVersionId,
                                    @Size(max = 100000) String externalResumeText,
                                    Long jobDescriptionId, @NotNull InterviewMode interviewMode,
                                    @jakarta.validation.constraints.Min(4) @jakarta.validation.constraints.Max(12) Integer targetQuestionCount,
                                    InterviewOutputLanguage outputLanguage) {}
