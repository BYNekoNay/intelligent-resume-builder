package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.InterviewStatus;

public record StartInterviewResponse(Long interviewId, String firstQuestion, InterviewStatus status) {}
