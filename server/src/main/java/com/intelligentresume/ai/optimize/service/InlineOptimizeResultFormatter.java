package com.intelligentresume.ai.optimize.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把模型的润色输出整理成编辑器能直接用的候选列表。
 *
 * <h2>这个类主要在做"减法"</h2>
 * 模型经常返回：和原文一样的、只改了空白的、重复的、超量的。这里逐条丢掉，最多留 3 条；
 * 一条都不剩时给 {@code emptyReason}，让前端有话说，而不是显示一块空白。
 *
 * <h2>三条不能松的规则</h2>
 * <ol>
 *   <li><b>比较前先去掉所有空白</b>：只改空格或换行的"优化"等于没改，却会让用户以为措辞变好了；</li>
 *   <li><b>永远 {@code requiresManualConfirmation=true}</b>：候选再合理也不能自动写回编辑器，这是产品红线；</li>
 *   <li><b>兼容旧任务</b>：早期任务只有 {@code optimizedText} 字段、没有 candidates 数组，
 *       这里补一条兜底，避免历史任务打开是空的。</li>
 * </ol>
 */
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

    /** 四种情况静默丢弃：超出 3 条、空白、与原文相同、重复。宁少给，不给假候选。 */
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

    /**
     * 去掉**所有**空白再比较：「春 招」与「春招」视为同一句。
     * 只调格式的候选不该占用一个候选位，更不该让用户以为采纳了实质修改。
     */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
