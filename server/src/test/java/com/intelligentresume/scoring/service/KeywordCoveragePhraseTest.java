package com.intelligentresume.scoring.service;

import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.scoring.rule.KeywordRule;
import com.intelligentresume.scoring.rule.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端单元回归：**含空格的多词 JD 关键词必须能命中**。
 *
 * <p><b>原缺陷</b>：简历侧只产出单词 token（{@code tokenize} 按单词切分），
 * 而 JD 关键词来自配置词典、可能是整短语（如 {@code Spring Boot}）；
 * 于是归一化后的 {@code "spring boot"} 永远不可能等于任何单个 token →
 * **两侧逐字相同也判缺失**，keywordCoverage 被系统性低估。
 *
 * <p>与既有 {@code KeywordRuleTest} 的关键区别：本测试走**真实链路**
 * （{@code ResumeKeywordExtractor} 从简历 JSON 抽 token → {@code KeywordRule} 评估），
 * 而不是手工构造 token 集合 —— 后者正是让该缺陷逃过测试的原因。
 *
 * <p>既有 3 类断言同时保留：多词命中、不依赖词典也能命中、**真正缺失仍判缺失**（防过度修复）。
 */
class KeywordCoveragePhraseTest {

    private ResumeKeywordExtractor extractor;
    private KeywordRule rule;

    @BeforeEach
    void setUp() {
        // 与 application.yml / application-test.yml 的 app.scoring.synonym-dictionary 保持一致
        Normalizer normalizer = new Normalizer(Map.of(
                "java", List.of("java", "jdk", "openjdk"),
                "spring", List.of("spring", "spring boot", "spring cloud"),
                "mysql", List.of("mysql", "mariadb"),
                "redis", List.of("redis")
        ));
        extractor = new ResumeKeywordExtractor(normalizer);
        rule = new KeywordRule(normalizer);
    }

    /** 用若干技能名构造简历版本（每个名称是独立的文本字段）。 */
    private ResumeVersion resumeWith(String... skillNames) {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (String name : skillNames) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            skills.add(s);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("skills", skills);
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(json);
        return version;
    }

    @Test
    @DisplayName("回归: 简历含 'Spring Boot' 时，JD 关键词 'Spring Boot' 必须命中而非缺失")
    void multiWordKeywordMatches() {
        ResumeVersion version = resumeWith("Spring Boot", "Java");

        Set<String> tokens = extractor.extract(version);
        Set<String> raw = extractor.extractRaw(version);
        KeywordRule.RuleResult result = rule.evaluate(
                List.of("Spring Boot", "Java", "Redis"), tokens, raw);

        assertTrue(result.matched().contains("Spring Boot"),
                "'Spring Boot' 与简历逐字相同却未命中。matched=" + result.matched()
                        + " partial=" + result.partialMatched()
                        + " missing=" + result.missing()
                        + " tokens=" + tokens);
        assertTrue(result.matched().contains("Java"));
        assertEquals(List.of("Redis"), result.missing(), "真正缺失的 Redis 仍应判缺失");
        assertEquals(0, new BigDecimal("66.67").compareTo(result.score()));
    }

    @Test
    @DisplayName("回归: 未配置同义词的多词关键词同样命中（靠词组，不依赖词典）")
    void multiWordKeywordMatchesWithoutDictionaryEntry() {
        ResumeVersion version = resumeWith("Spring Security");

        KeywordRule.RuleResult result = rule.evaluate(
                List.of("Spring Security"), extractor.extract(version), extractor.extractRaw(version));

        assertTrue(result.matched().contains("Spring Security"),
                "未配置同义词的多词关键词应通过连续词组直接命中。missing=" + result.missing()
                        + " tokens=" + extractor.extract(version));
        assertTrue(result.missing().isEmpty());
    }

    @Test
    @DisplayName("防过度修复: 词在简历中不相邻时不得当成词组命中")
    void nonAdjacentWordsStillMissing() {
        // "Spring" 与 "Security" 是两个独立字段 → 不构成连续词组 → 应判缺失
        ResumeVersion version = resumeWith("Spring", "Security");

        KeywordRule.RuleResult result = rule.evaluate(
                List.of("Spring Security"), extractor.extract(version), extractor.extractRaw(version));

        assertEquals(List.of("Spring Security"), result.missing(),
                "不相邻的词不应被当成词组命中，否则会过度修复成假命中。matched=" + result.matched()
                        + " partial=" + result.partialMatched());
    }

    @Test
    @DisplayName("防过度修复: 真正缺失的关键词（含中文）仍判缺失")
    void trulyMissingStillMissing() {
        ResumeVersion version = resumeWith("Java");

        KeywordRule.RuleResult result = rule.evaluate(
                List.of("Java", "Kubernetes", "微服务"),
                extractor.extract(version), extractor.extractRaw(version));

        assertTrue(result.missing().contains("Kubernetes"));
        assertTrue(result.missing().contains("微服务"));
        assertFalse(result.missing().contains("Java"));
    }

    // ---- 健壮性回归：畸形输入必须静默忽略，不得抛异常 ----
    // 预期行为依据 ResumeKeywordExtractor 的 instanceof 防御：
    // 非 Map 的数组元素 / 非 List 的 skills / null 文本字段一律跳过。

    @Test
    @DisplayName("健壮性: skills 数组元素含 null 与数字时静默忽略，不抛异常")
    void skillsElementsWithNullAndNumberAreSilentlyIgnored() {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name", "Java");
        List<Object> skills = new ArrayList<>();
        skills.add(null);
        skills.add(42);
        skills.add("Spring Boot");
        skills.add(skill);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("skills", skills);
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(json);

        Set<String> tokens = assertDoesNotThrow(() -> extractor.extract(version),
                "null/数字元素不得导致抽取抛异常");
        // "Spring Boot" 元素产出 spring 与 boot 两个单词 token；null 与 42 被静默忽略
        assertEquals(Set.of("spring", "boot", "java"), tokens,
                "合法项应被抽取，null 与数字元素应被静默忽略。tokens=" + tokens);
    }

    @Test
    @DisplayName("健壮性: skills 为字符串而非数组时整体静默忽略，产出空 token 集")
    void skillsAsStringIsSilentlyIgnored() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("skills", "Java, Spring Boot");
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(json);

        Set<String> tokens = assertDoesNotThrow(() -> extractor.extract(version),
                "skills 非数组不得抛异常");
        assertTrue(tokens.isEmpty(),
                "skills 为字符串时应整体静默忽略（与既有 instanceof 防御一致），产出空结果。tokens=" + tokens);
    }

    @Test
    @DisplayName("健壮性: basics 字段为 null、work 数组元素为 null 时静默忽略")
    void basicsAndWorkWithNullsAreSilentlyIgnored() {
        Map<String, Object> basics = new LinkedHashMap<>();
        basics.put("label", null);
        basics.put("summary", "Java 开发");

        Map<String, Object> workItem = new LinkedHashMap<>();
        workItem.put("position", "Backend Engineer");
        workItem.put("company", null);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("basics", basics);
        json.put("work", Arrays.asList(null, workItem));
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(json);

        Set<String> tokens = assertDoesNotThrow(() -> extractor.extract(version),
                "null 字段/null 数组元素不得抛异常");
        assertTrue(tokens.contains("java"), "basics.summary 的 'Java' 应被抽取。tokens=" + tokens);
        assertTrue(tokens.contains("backend"), "work[1].position 的 'Backend' 应被抽取。tokens=" + tokens);
        assertTrue(tokens.contains("engineer"), "work[1].position 的 'Engineer' 应被抽取。tokens=" + tokens);
    }

    @Test
    @DisplayName("健壮性: 极端长单词（约 256KB 简历上限内的 20 万字符 token）不抛异常且完整产出")
    void extremelyLongTokenIsHandled() {
        // 20 万字符 < JsonResumeValidator 的 262144 字节上限，属于合法简历可容纳的极端 token
        String longWord = "a".repeat(200_000);
        ResumeVersion version = resumeWith(longWord);

        Set<String> tokens = assertDoesNotThrow(() -> extractor.extract(version),
                "超长 token 不得导致抽取抛异常");
        assertEquals(Set.of(longWord), tokens,
                "长单词经小写化与去标点后应原样保留。tokens.size=" + tokens.size());

        Set<String> raw = assertDoesNotThrow(() -> extractor.extractRaw(version));
        assertEquals(Set.of(longWord), raw, "原始 token 集合同样应完整产出");
    }

    private ResumeVersion resumeWithSkills(List<Map<String, Object>> skills) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("skills", skills);
        ResumeVersion version = new ResumeVersion();
        version.setResumeJson(json);
        return version;
    }

    @Test
    @DisplayName("回归: skills 的规范字段 items 必须被抽取（历史实现只读 keywords → 技能项永远抽不到）")
    void skillsItemsAreExtracted() {
        // 结构取自 JobGenerationPromptBuilder 的示例：
        // {"name": "...", "category": "...", "items": [...], "level": "..."}
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name", "Java");
        skill.put("category", "后端");
        skill.put("items", List.of("Java", "Spring Boot"));
        skill.put("level", "熟练");
        ResumeVersion version = resumeWithSkills(List.of(skill));

        Set<String> skillTokens = extractor.extractSkillTokens(version);
        assertTrue(skillTokens.contains("spring"),
                "skills[*].items 中的 'Spring Boot' 未被抽取（词典生效时应归一为 spring）。tokens=" + skillTokens);
        assertTrue(skillTokens.contains("java"));

        KeywordRule.RuleResult result = rule.evaluate(
                List.of("Spring Boot", "Java"), skillTokens, extractor.extractSkillRaw(version));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(result.score()),
                "技能区已包含两个 JD 技能，技能覆盖度应满分。matched=" + result.matched()
                        + " missing=" + result.missing());
    }

    @Test
    @DisplayName("回归: skills 的别名字段 keywords 与纯字符串项仍被抽取（向后兼容）")
    void skillsAliasesStillExtracted() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("name", "Java");
        legacy.put("keywords", List.of("Spring Boot"));
        ResumeVersion version = resumeWithSkills(List.of(legacy, stringSkill("Redis")));

        Set<String> skillTokens = extractor.extractSkillTokens(version);
        assertTrue(skillTokens.contains("spring"), "keywords 别名应仍被抽取。tokens=" + skillTokens);
        assertTrue(skillTokens.contains("redis"), "纯字符串技能项应仍被抽取。tokens=" + skillTokens);
    }

    private Map<String, Object> stringSkill(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }
}
