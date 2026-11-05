package com.intelligentresume.ats.dto;

import java.util.List;

/**
 * AI 深度分析的结论（只做语义补充，<b>不含任何分数或录用结论</b>）。
 *
 * <p>要点：
 * <ul>
 *   <li>模型不得返回分数/录用概率——规则分是权威，这一点由 {@code AtsAiResultValidator} 的字段白名单
 *       强制执行：多一个字段整份拒绝。</li>
 *   <li>{@code semanticCoverage.status} 只允许 MATCHED / PARTIAL / MISSING；MISSING 时 evidence 可为 null。</li>
 *   <li>{@code prioritizedActions.priority} 只允许 P0 / P1 / P2；{@code confidence} 只允许 LOW / MEDIUM / HIGH。</li>
 *   <li>数量上限：semanticCoverage ≤ 20、evidenceFindings ≤ 12、readabilityRisks ≤ 10、prioritizedActions ≤ 8
 *       （提示词里写死，validator 会复核）。</li>
 *   <li>{@code quote} 是简历原文片段，用于"指到具体一句"；validator 会把不在简历序列化文本里的引文
 *       直接置 null（防编造），因此前端要允许它为 null。</li>
 * </ul>
 */
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
