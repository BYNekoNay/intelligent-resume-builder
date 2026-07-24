package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KeywordRule 单元测试。
 * 覆盖：完全命中、同义词命中、缺失、大小写不敏感、关键词堆砌、空关键词。
 */
class KeywordRuleTest {

    private KeywordRule rule;

    @BeforeEach
    void setUp() {
        Normalizer normalizer = new Normalizer(Map.of(
                "spring", List.of("spring", "spring boot", "spring cloud"),
                "java", List.of("java", "jdk", "openjdk"),
                "mysql", List.of("mysql", "mariadb"),
                "k8s", List.of("k8s", "kubernetes")
        ));
        rule = new KeywordRule(normalizer);
    }

    @Test
    @DisplayName("正常路径: 完全命中计 matched")
    void matched_counted() {
        List<String> jdKeywords = List.of("Java", "MySQL");
        Set<String> resumeTokens = Set.of("java", "mysql", "redis");
        Set<String> resumeRaw = Set.of("java", "mysql", "redis");

        KeywordRule.RuleResult result = rule.evaluate(jdKeywords, resumeTokens, resumeRaw);

        assertEquals(2, result.matched().size());
        assertTrue(result.matched().contains("Java"));
        assertTrue(result.matched().contains("MySQL"));
        assertTrue(result.missing().isEmpty());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(result.score()));
    }

    @Test
    @DisplayName("正常路径: 同义词命中计 partialMatched")
    void synonym_partialMatched() {
        // JD 要求 "Spring Boot"，简历有 "Spring"（同义词归一后都是 "spring"）
        List<String> jdKeywords = List.of("Spring Boot");
        Set<String> resumeTokens = Set.of("spring"); // 归一化后
        Set<String> resumeRaw = Set.of("spring");    // 原始没有 "spring boot"

        KeywordRule.RuleResult result = rule.evaluate(jdKeywords, resumeTokens, resumeRaw);

        assertEquals(1, result.partialMatched().size());
        assertTrue(result.partialMatched().contains("Spring Boot"));
        assertTrue(result.matched().isEmpty());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(result.score()));
    }

    @Test
    @DisplayName("正常路径: JD 中独有但简历无计 missing")
    void missing_counted() {
        List<String> jdKeywords = List.of("Java", "Kubernetes", "Redis");
        Set<String> resumeTokens = Set.of("java");
        Set<String> resumeRaw = Set.of("java");

        KeywordRule.RuleResult result = rule.evaluate(jdKeywords, resumeTokens, resumeRaw);

        assertEquals(1, result.matched().size());
        assertEquals(2, result.missing().size());
        assertTrue(result.missing().contains("Kubernetes"));
        assertTrue(result.missing().contains("Redis"));
        // 1/3 ≈ 33.33
        assertTrue(result.score().compareTo(BigDecimal.valueOf(50)) < 0);
    }

    @Test
    @DisplayName("正常路径: 大小写差异不命中 missing")
    void caseInsensitive_noMissing() {
        List<String> jdKeywords = List.of("JAVA", "mysql");
        Set<String> resumeTokens = Set.of("java", "mysql");
        Set<String> resumeRaw = Set.of("java", "mysql");

        KeywordRule.RuleResult result = rule.evaluate(jdKeywords, resumeTokens, resumeRaw);

        assertTrue(result.missing().isEmpty());
        assertEquals(2, result.matched().size());
    }

    @Test
    @DisplayName("边界路径: 关键词堆砌(简历出现关键词 50 次)不影响分数")
    void keywordStuffing_ignored() {
        List<String> jdKeywords = List.of("Java");
        // Set 去重，堆砌无效
        Set<String> resumeTokens = Set.of("java");
        Set<String> resumeRaw = Set.of("java");

        KeywordRule.RuleResult result = rule.evaluate(jdKeywords, resumeTokens, resumeRaw);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(result.score()));
        assertEquals(1, result.matched().size());
    }

    @Test
    @DisplayName("边界路径: 关键词为空时 keyword_score=100,missing=[]")
    void emptyJdKeyword_score100() {
        KeywordRule.RuleResult result = rule.evaluate(List.of(), Set.of("java"), Set.of("java"));

        assertEquals(0, BigDecimal.valueOf(100).compareTo(result.score()));
        assertTrue(result.missing().isEmpty());
        assertTrue(result.matched().isEmpty());
    }
}
