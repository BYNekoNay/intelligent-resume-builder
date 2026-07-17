package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 经历匹配:从 JD 解析年限要求 + 从简历抽出最长工作年限,差值不大于 0 时满分,差值越大得分越低。
 */
@Component
public class ExperienceRule implements ScoringRule {

    private static final Pattern YEARS = Pattern.compile(
            "(?:(\\d{1,2})\\s*(?:\\+)?\\s*(?:years?|yrs?|年))",
            Pattern.CASE_INSENSITIVE);

    private final Normalizer normalizer;

    public ExperienceRule(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public String name() { return "experience"; }

    @Override
    public BigDecimal score(Set<String> jdTokens, Set<String> resumeTokens, Map<String, Object> jdMeta) {
        int jdYears = parseYears(String.join(" ", jdTokens));
        int resumeYears = 0;
        Object resumeYearsMeta = jdMeta == null ? null : jdMeta.get("resumeYears");
        if (resumeYearsMeta instanceof Number n) {
            resumeYears = n.intValue();
        } else {
            resumeYears = parseYears(String.join(" ", resumeTokens));
        }
        if (jdYears <= 0) return new BigDecimal("100.00");
        int delta = jdYears - resumeYears;
        if (delta <= 0) return new BigDecimal("100.00");
        // 每多 1 年扣 10 分,最低 0
        BigDecimal v = BigDecimal.valueOf(Math.max(0, 100 - delta * 10));
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public List<String> matched(Set<String> jdTokens, Set<String> resumeTokens) {
        int jdYears = parseYears(String.join(" ", jdTokens));
        int resumeYears = parseYears(String.join(" ", resumeTokens));
        if (jdYears <= 0) return List.of();
        return resumeYears >= jdYears ? List.of(jdYears + " 年以上相关经验") : List.of();
    }

    @Override
    public List<String> partialMatched(Set<String> jdTokens, Set<String> resumeTokens) {
        int jdYears = parseYears(String.join(" ", jdTokens));
        int resumeYears = parseYears(String.join(" ", resumeTokens));
        if (jdYears <= 0 || resumeYears >= jdYears) return List.of();
        return List.of(jdYears + " 年(" + resumeYears + ")");
    }

    @Override
    public List<String> missing(Set<String> jdTokens, Set<String> resumeTokens) {
        int jdYears = parseYears(String.join(" ", jdTokens));
        int resumeYears = parseYears(String.join(" ", resumeTokens));
        if (jdYears <= 0 || resumeYears >= jdYears) return List.of();
        return List.of("差 " + (jdYears - resumeYears) + " 年经验");
    }

    private int parseYears(String text) {
        if (text == null || text.isBlank()) return 0;
        Matcher m = YEARS.matcher(text);
        int max = 0;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > max && v < 60) max = v;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }
}
