package com.intelligentresume.ats.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AtsAiResultValidator {
    private static final Set<String> TOP_LEVEL = Set.of(
            "summary", "semanticCoverage", "evidenceFindings", "readabilityRisks", "prioritizedActions", "confidence");
    private static final Set<String> COVERAGE_KEYS = Set.of("requirement", "status", "evidence", "reason");
    private static final Set<String> EVIDENCE_KEYS = Set.of("section", "quote", "assessment", "suggestion");
    private static final Set<String> ACTION_KEYS = Set.of("priority", "section", "action", "basis");
    private final ObjectMapper objectMapper;

    public AtsAiResultValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AtsAiInsights validate(Map<String, Object> output, Object resumeJson) {
        if (output == null || !output.keySet().equals(TOP_LEVEL)) throw fail("Unexpected ATS AI response fields");
        String summary = text(output.get("summary"), "summary", 500);
        String confidence = enumText(output.get("confidence"), "confidence", Set.of("LOW", "MEDIUM", "HIGH"));

        List<AtsAiInsights.SemanticCoverage> coverage = new ArrayList<>();
        for (Map<String, Object> item : objects(output.get("semanticCoverage"), "semanticCoverage", 20, COVERAGE_KEYS)) {
            coverage.add(new AtsAiInsights.SemanticCoverage(
                    text(item.get("requirement"), "requirement", 300),
                    enumText(item.get("status"), "status", Set.of("MATCHED", "PARTIAL", "MISSING")),
                    optionalText(item.get("evidence"), 500),
                    text(item.get("reason"), "reason", 600)));
        }

        List<AtsAiInsights.EvidenceFinding> findings = new ArrayList<>();
        String resumeText = null;
        for (Map<String, Object> item : objects(output.get("evidenceFindings"), "evidenceFindings", 12, EVIDENCE_KEYS)) {
            String quote = optionalText(item.get("quote"), 500);
            if (quote != null) {
                if (resumeText == null) resumeText = serialize(resumeJson);
                if (!resumeText.contains(quote)) quote = null;
            }
            findings.add(new AtsAiInsights.EvidenceFinding(
                    text(item.get("section"), "section", 120), quote,
                    text(item.get("assessment"), "assessment", 600),
                    text(item.get("suggestion"), "suggestion", 800)));
        }

        List<String> risks = strings(output.get("readabilityRisks"), "readabilityRisks", 10, 500);
        List<AtsAiInsights.PrioritizedAction> actions = new ArrayList<>();
        for (Map<String, Object> item : objects(output.get("prioritizedActions"), "prioritizedActions", 8, ACTION_KEYS)) {
            actions.add(new AtsAiInsights.PrioritizedAction(
                    enumText(item.get("priority"), "priority", Set.of("P0", "P1", "P2")),
                    text(item.get("section"), "section", 120),
                    text(item.get("action"), "action", 800),
                    text(item.get("basis"), "basis", 600)));
        }
        return new AtsAiInsights(summary, coverage, findings, risks, actions, confidence);
    }

    private List<Map<String, Object>> objects(Object value, String name, int max, Set<String> keys) {
        if (!(value instanceof List<?> list) || list.size() > max) throw fail(name + " must be an array of at most " + max);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) throw fail(name + " contains a non-object item");
            Map<String, Object> converted = objectMapper.convertValue(item, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
            if (!converted.keySet().equals(keys)) throw fail(name + " contains unexpected fields");
            result.add(converted);
        }
        return result;
    }

    private List<String> strings(Object value, String name, int maxItems, int maxLength) {
        if (!(value instanceof List<?> list) || list.size() > maxItems) throw fail(name + " must be an array of at most " + maxItems);
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            if (item instanceof String text && text.isBlank()) continue;
            result.add(text(item, name, maxLength));
        }
        return result;
    }

    private String enumText(Object value, String name, Set<String> allowed) {
        String text = text(value, name, 32);
        if (!allowed.contains(text)) throw fail(name + " has an invalid value");
        return text;
    }

    private String optionalText(Object value, int maxLength) {
        if (value == null) return null;
        if (value instanceof String text && text.isBlank()) return null;
        return text(value, "optional text", maxLength);
    }

    private String text(Object value, String name, int maxLength) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw fail(name + " must be a non-blank string");
        }
        String normalized = text.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw fail("Resume evidence could not be inspected");
        }
    }

    private AtsAiAnalysisException fail(String message) {
        return new AtsAiAnalysisException(AtsFallbackCode.INVALID_RESPONSE, message, false);
    }
}
