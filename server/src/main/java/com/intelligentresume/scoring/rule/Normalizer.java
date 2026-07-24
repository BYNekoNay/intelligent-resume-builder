package com.intelligentresume.scoring.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 归一化器：小写、去标点、折叠空格、同义词归一。
 *
 * <p>同义词词典格式（配置注入）：canonical → [synonym1, synonym2, ...]。
 * 例如 {@code spring: ["spring", "spring boot", "spring cloud"]}，
 * 则 "Spring Boot" 归一化为 "spring"。
 */
@Component
public class Normalizer {

    private static final Pattern CHINESE_SEQ = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z0-9]+(?:[\\-][a-zA-Z0-9]+)*");

    /** canonical → 所有同义词（含自身），全部小写 */
    private final Map<String, List<String>> synonymMap;
    /** 反向索引：synonym(小写) → canonical */
    private final Map<String, String> reverseIndex;

    public Normalizer(
            @Value("#{${app.scoring.synonym-dictionary:{}}}") Map<String, List<String>> synonymDictionary) {
        this.synonymMap = new LinkedHashMap<>();
        this.reverseIndex = new HashMap<>();
        if (synonymDictionary != null) {
            synonymDictionary.forEach((canonical, synonyms) -> {
                String canonLower = canonical.toLowerCase().trim();
                List<String> lowerSynonyms = new ArrayList<>();
                for (String s : synonyms) {
                    String sl = s.toLowerCase().trim();
                    lowerSynonyms.add(sl);
                    reverseIndex.put(sl, canonLower);
                }
                synonymMap.put(canonLower, lowerSynonyms);
            });
        }
    }

    /**
     * 归一化单个 token：小写 → 去标点 → 折叠空格 → 同义词归一。
     */
    public String normalize(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        // 小写
        String lower = token.toLowerCase();
        // 去标点（保留字母数字中文空格连字符）
        lower = lower.replaceAll("[^\\p{L}\\p{N}\\s\\-]", "");
        // 折叠空格
        lower = lower.replaceAll("\\s+", " ").trim();
        // 同义词归一
        String canonical = reverseIndex.get(lower);
        return canonical != null ? canonical : lower;
    }

    /**
     * 分词：英文按单词，中文按连续序列。
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        // 英文单词
        Matcher engMatcher = ENGLISH_WORD.matcher(text);
        while (engMatcher.find()) {
            tokens.add(engMatcher.group());
        }
        // 中文连续序列
        Matcher chMatcher = CHINESE_SEQ.matcher(text);
        while (chMatcher.find()) {
            tokens.add(chMatcher.group());
        }
        return tokens;
    }

    /**
     * 将文本分词并归一化，返回归一化 token 集合。
     */
    public Set<String> normalizeAll(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : tokenize(text)) {
            String normalized = normalize(token);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 判断原始 token 是否为直接匹配（非通过同义词）。
     */
    public boolean isDirectMatch(String jdKeyword, Set<String> resumeRawTokens) {
        String cleaned = jdKeyword.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s\\-]", "").replaceAll("\\s+", " ").trim();
        return resumeRawTokens.contains(cleaned);
    }
}
