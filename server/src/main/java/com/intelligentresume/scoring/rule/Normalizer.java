package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本归一化:大小写折叠 + 去标点 + 折叠空白 + 同义词字典。
 *
 * <p>同义词字典以小写 key 索引,所有同义词的"主词"为字典第一个出现的 token。
 * 实际求值时,两份 tokens 都先经过 {@link #normalize(String)} 折叠,然后再用
 * {@link #canonical(String)} 映射到主词。
 */
@Component
public class Normalizer {

    /** 同义词典:key = 小写形式;value = 主词。 */
    private static final Map<String, String> SYNONYMS = buildDefaultSynonyms();

    private static final Pattern PUNCT = Pattern.compile("[\\p{Punct}&&[^.+#/-]]+");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    public String normalize(String token) {
        if (token == null) return "";
        String s = token.toLowerCase(Locale.ROOT).trim();
        s = PUNCT.matcher(s).replaceAll(" ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String norm = normalize(text);
        if (norm.isBlank()) return List.of();
        String[] parts = norm.split(" ");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) tokens.add(canonical(p));
        }
        return tokens;
    }

    public Set<String> distinctTokens(String text) {
        return new LinkedHashSet<>(tokenize(text));
    }

    public String canonical(String token) {
        String norm = normalize(token);
        return SYNONYMS.getOrDefault(norm, norm);
    }

    private static Map<String, String> buildDefaultSynonyms() {
        Map<String, String> m = new HashMap<>();
        // 后端语言 / 框架同义
        put(m, "spring boot", "spring boot");
        put(m, "spring-boot", "spring boot");
        put(m, "springboot", "spring boot");
        put(m, "jpa", "jpa");
        put(m, "hibernate", "hibernate");
        // JS 前端
        put(m, "js", "javascript");
        put(m, "typescript", "typescript");
        put(m, "ts", "typescript");
        put(m, "vue", "vue");
        put(m, "vue.js", "vue");
        put(m, "react", "react");
        // 容器
        put(m, "k8s", "kubernetes");
        put(m, "kubernetes", "kubernetes");
        // 数据库
        put(m, "postgres", "postgresql");
        put(m, "mysql", "mysql");
        put(m, "mongo", "mongodb");
        // CI
        put(m, "ci cd", "ci/cd");
        put(m, "cicd", "ci/cd");
        return m;
    }

    private static void put(Map<String, String> m, String key, String canonical) {
        m.put(key, canonical);
    }
}
