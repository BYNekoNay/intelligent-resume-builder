package com.intelligentresume.interview.dto;

import java.util.List;

public record InterviewReportResponse(Integer totalScore, String summary, List<String> strengths,
                                      List<String> weaknesses, List<String> resumeSuggestions,
                                      List<String> expressionSuggestions) {}
