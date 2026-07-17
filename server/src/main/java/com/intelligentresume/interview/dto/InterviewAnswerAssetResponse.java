package com.intelligentresume.interview.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record InterviewAnswerAssetResponse(Long id, Long interviewRecordId, String questionText,
        String originalAnswerText, String suggestedAnswerText, Map<String, Object> feedbackJson,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
