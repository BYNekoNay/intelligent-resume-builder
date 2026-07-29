package com.intelligentresume.ats.dto;

import java.util.List;

public record AtsAiInsights(
        String summary,
        List<SemanticCoverage> semanticCoverage,
        List<EvidenceFinding> evidenceFindings,
        List<String> readabilityRisks,
        List<PrioritizedAction> prioritizedActions,
        String confidence
) {
    public record SemanticCoverage(
            String requirement,
            String status,
            String evidence,
            String reason
    ) {
    }

    public record EvidenceFinding(
            String section,
            String quote,
            String assessment,
            String suggestion
    ) {
    }

    public record PrioritizedAction(
            String priority,
            String section,
            String action,
            String basis
    ) {
    }
}
