package com.intelligentresume.ai.optimize.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normalizes provider output into the editor's stable inline-optimization contract. */
@Component
public class InlineOptimizeResultFormatter {

    private static final int MAX_CANDIDATES = 3;

    public Map<String, Object> format(Map<String, Object> input, Map<String, Object> providerResult) {
        String original = firstText(input, "content", "text");
        List<Map<String, String>> candidates = distinctCandidates(original, providerResult);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalContent", original);
        result.put("candidates", candidates);
        result.put("requiresManualConfirmation", true);
        if (candidates.isEmpty()) {
            result.put("emptyReason", "AI could not produce a different wording without adding unsupported facts.");
        }
        return result;
    }

    private List<Map<String, String>> distinctCandidates(String original, Map<String, Object> providerResult) {
        List<Map<String, String>> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String normalizedOriginal = normalize(original);

        Object rawCandidates = providerResult == null ? null : providerResult.get("candidates");
        if (rawCandidates instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> item) {
                    addCandidate(candidates, seen, normalizedOriginal,
                            text(item.get("content")), text(item.get("suggestion")));
                }
            }
        }

        // Compatibility with tasks completed before the candidates contract was introduced.
        if (candidates.isEmpty() && providerResult != null) {
            addCandidate(candidates, seen, normalizedOriginal,
                    firstText(providerResult, "optimizedText", "optimizedContent"), "优化表达，保持原始事实不变");
        }
        return candidates;
    }

    private void addCandidate(List<Map<String, String>> candidates, Set<String> seen, String normalizedOriginal,
                              String content, String suggestion) {
        if (candidates.size() >= MAX_CANDIDATES || content.isBlank()) return;
        String normalized = normalize(content);
        if (normalized.isBlank() || normalized.equals(normalizedOriginal) || !seen.add(normalized)) return;
        candidates.add(Map.of("content", content.trim(), "suggestion",
                suggestion.isBlank() ? "优化表达，保持原始事实不变" : suggestion.trim()));
    }

    private String firstText(Map<String, Object> source, String... keys) {
        if (source == null) return "";
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
