package com.intelligentresume.interview.dto;

public record AnswerInterviewResponse(Long recordId, String questionText, Integer roundScore,
                                      InterviewFeedback feedback, String nextQuestion) {}
