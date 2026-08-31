package com.intelligentresume.interview.asset.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record InterviewAssetResponse(Long id, Long interviewRecordId, String questionText,
                                     String originalAnswerText, String suggestedAnswerText,
                                     Map<String, Object> feedbackJson, LocalDateTime createdAt,
                                     LocalDateTime updatedAt,
                                     List<String> sectionKeys, List<Long> materialIds) {}
