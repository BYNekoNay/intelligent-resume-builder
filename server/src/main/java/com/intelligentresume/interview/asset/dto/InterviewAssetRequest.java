package com.intelligentresume.interview.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record InterviewAssetRequest(Long interviewRecordId,
                                    @NotBlank @Size(max = 10000) String questionText,
                                    @NotBlank @Size(max = 20000) String originalAnswerText,
                                    @Size(max = 20000) String suggestedAnswerText,
                                    Map<String, Object> feedbackJson) {}
