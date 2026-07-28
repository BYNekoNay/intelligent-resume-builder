package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnswerInterviewRequest(@NotBlank @Size(max = 20000) String answer) {}
