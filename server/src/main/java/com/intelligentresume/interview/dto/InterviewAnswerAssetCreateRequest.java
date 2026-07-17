package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record InterviewAnswerAssetCreateRequest(Long interviewRecordId,
        @NotBlank @Size(max = 10000) String questionText,
        @NotBlank @Size(max = 30000) String originalAnswerText,
        @Size(max = 30000) String suggestedAnswerText,
        Map<String, Object> feedbackJson) {
}
