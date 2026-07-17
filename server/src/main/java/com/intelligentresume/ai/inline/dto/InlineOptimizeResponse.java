package com.intelligentresume.ai.inline.dto;

import java.util.List;

public record InlineOptimizeResponse(
        Long recordId,
        String section,
        String originalContent,
        List<Candidate> candidates,
        boolean requiresManualConfirmation
) {
    public record Candidate(String content, String suggestion) {
    }
}
