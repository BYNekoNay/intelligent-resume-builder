package com.intelligentresume.scoring.rule;

import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 一趟扫描的有序分词模式。
     *
     * <p>两个分支的字符集不相交（拉丁数字词 vs 中文序列），因此与分两趟扫描
     * （{@link #tokenize(String)}）得到的**成员集合完全相同**，差别只在顺序 ——
     * 而词组匹配依赖顺序，故需要本模式。
     */
    private static final Pattern ORDERED_TOKEN =
            Pattern.compile("[a-zA-Z0-9]+(?:\\-[a-zA-Z0-9]+)*|[\\u4e00-\\u9fff]+");

    /**
     * 词组匹配支持的最大词数。
     *
     * <p>取 5 是权衡：覆盖 {@code Spring Boot}／{@code Spring Cloud}／{@code Node Js}
     * 这类常见技术短语，同时把词组数量控制在「词数 × 5」量级；
     * 更长的 JD 关键词仍可由同义词词典命中。
     */
    public static final int MAX_PHRASE_WORDS = 5;

    /** canonical → 所有同义词（含自身），全部小写 */
    private final Map<String, List<String>> synonymMap;
    /** 反向索引：synonym(小写) → canonical */
    private final Map<String, String> reverseIndex;

    /** 供单元测试与显式构造使用。 */
    public Normalizer(Map<String, List<String>> synonymDictionary) {
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
     * Spring 注入入口：从 {@code app.scoring} 绑定同义词词典。
     *
     * <p>此处**不能**用 {@code @Value("#{${app.scoring.synonym-dictionary:{}}}")} ——
     * 该写法对嵌套 YAML 无效，会让词典恒为空（完整分析见 {@link ScoringProperties}）。
     */
    @Autowired
    public Normalizer(ScoringProperties properties) {
        this(properties.getSynonymDictionary());
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
     * 有序分词：按出现顺序返回拉丁/数字词与中文连续序列。
     *
     * <p>与 {@link #tokenize(String)} 的关系：成员集合相同，但保留出现顺序。
     */
    public List<String> tokenizeOrdered(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = ORDERED_TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * 有序分词 + 连续词组。
     *
     * <p><b>为什么需要词组</b>：JD 关键词来自配置词典，可能是**含空格的多词短语**
     * （如 {@code Spring Boot}）。而简历侧若只产出单词 token，归一化后的
     * {@code "spring boot"} 永远不可能等于任何单个 token →
     * **即使两侧逐字相同也必然判缺失**，keywordCoverage 被系统性低估。
     *
     * <p>词组只在**同一段文本内连续**的拉丁/数字词之间生成：中文序列不参与组词
     * （中文无空格分隔，组词无意义，且中文序列本身已是完整 token）。
     * 这样 {@code "精通 Spring Boot 与 Redis"} 能产出 {@code "spring boot"}，
     * 但不会产出 {@code "boot redis"} 这类跨中文的伪词组。
     *
     * @param maxWords 词组最多包含的词数（自本词起连续）
     */
    public List<String> tokenizeWithPhrases(String text, int maxWords) {
        List<String> ordered = tokenizeOrdered(text);
        List<String> result = new ArrayList<>(ordered);
        int limit = Math.max(1, maxWords);
        for (int i = 0; i < ordered.size(); i++) {
            if (!isLatinWord(ordered.get(i))) {
                continue;
            }
            StringBuilder phrase = new StringBuilder(ordered.get(i));
            for (int j = i + 1; j < ordered.size() && j - i < limit; j++) {
                if (!isLatinWord(ordered.get(j))) {
                    break;
                }
                phrase.append(' ').append(ordered.get(j));
                result.add(phrase.toString());
            }
        }
        return result;
    }

    /** 是否为拉丁/数字词（中文序列不参与组词）。 */
    private static boolean isLatinWord(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        char c = token.charAt(0);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 判断原始 token 是否为直接匹配（非通过同义词）。
     */
    public boolean isDirectMatch(String jdKeyword, Set<String> resumeRawTokens) {
        String cleaned = jdKeyword.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s\\-]", "").replaceAll("\\s+", " ").trim();
        return resumeRawTokens.contains(cleaned);
    }
}
