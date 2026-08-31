package com.intelligentresume.ai.optimize.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineOptimizeResultFormatterTest {

    private final InlineOptimizeResultFormatter formatter = new InlineOptimizeResultFormatter();

    @Test
    void convertsLegacyOptimizedTextToEditorCandidate() {
        Map<String, Object> result = formatter.format(
                Map.of("content", "负责订单系统开发"),
                Map.of("optimizedText", "主导订单系统的核心功能开发"));

        List<Map<String, String>> candidates = candidates(result);
        assertEquals(1, candidates.size());
        assertEquals("主导订单系统的核心功能开发", candidates.get(0).get("content"));
        assertTrue((Boolean) result.get("requiresManualConfirmation"));
    }

    @Test
    void removesOriginalAndDuplicateCandidates() {
        Map<String, Object> result = formatter.format(
                Map.of("content", "负责订单系统开发"),
                Map.of("candidates", List.of(
                        Map.of("content", "负责订单系统开发", "suggestion", "重复原文"),
                        Map.of("content", "主导订单系统开发", "suggestion", "强化动作"),
                        Map.of("content", "主导 订单系统开发", "suggestion", "重复候选"))));

        List<Map<String, String>> candidates = candidates(result);
        assertEquals(1, candidates.size());
        assertEquals("主导订单系统开发", candidates.get(0).get("content"));
    }

    @Test
    void explainsWhenProviderOnlyReturnsOriginalText() {
        Map<String, Object> result = formatter.format(
                Map.of("content", "负责订单系统开发"),
                Map.of("optimizedText", "负责订单系统开发"));

        assertTrue(candidates(result).isEmpty());
        assertFalse(((String) result.get("emptyReason")).isBlank());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> candidates(Map<String, Object> result) {
        return (List<Map<String, String>>) result.get("candidates");
    }
}
