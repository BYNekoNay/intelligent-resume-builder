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

/**
 * 校验 AI 返回的 ATS 分析结果：模型的自由文本在这里被翻译成可信数据，或者被拒绝。
 *
 * <h2>要防的三件事</h2>
 * <ol>
 *   <li><b>字段乱造</b>：顶层字段必须**完全等于** {@code TOP_LEVEL} 那 6 个（多一个或漏一个都拒绝），
 *       数组里每一项的字段集合也要完全一致；</li>
 *   <li><b>输出失控</b>：每个数组都有条数上限（覆盖 20 / 证据 12 / 可读性 10 / 建议 8），
 *       每段文本都有长度上限，超长是**截断**而不是报错（文本长了不该让整次分析作废）；</li>
 *   <li><b>编造原文</b>：{@code evidenceFindings[].quote} 必须能在简历 JSON 里逐字找到，找不到就置空 ——
 *       否则界面会展示一段"用户简历里根本不存在"的引文。</li>
 * </ol>
 *
 * <h2>失败之后</h2>
 * 任何不合规都抛 {@link AtsAiAnalysisException}（{@code INVALID_RESPONSE}，标记为不可重试），
 * 上层据此降级成规则结果并告知原因。<b>宁可不给 AI 结论，也不给错的结论。</b>
 *
 * <p>边界：本类只校验结构与引文真实性，不评价分析质量 —— 观点对不对，规则管不了。
 */
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
                // 防编造：模型给的"原文引用"必须真的出现在简历里。
                // 这里只做序列化后的逐字子串比对，不做模糊匹配 —— 宁可丢掉一条证据，
                // 也不能让界面展示一段用户简历里不存在的引文。
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
