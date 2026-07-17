package com.intelligentresume.interview.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record InterviewAnswerRequest(@NotBlank @Size(max=30000) String answer){}
