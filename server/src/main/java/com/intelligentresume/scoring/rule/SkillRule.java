package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能匹配规则:仅当 jdToken 与 resumeToken 完全一致(归一化后)才算 matched,不支持部分匹配。
 */
@Component
public class SkillRule implements ScoringRule {

    private final Normalizer normalizer;

    public SkillRule(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public String name() { return "skill"; }

    @Override
    public BigDecimal score(Set<String> jdTokens, Set<String> resumeTokens, Map<String, Object> jdMeta) {
        if (jdTokens.isEmpty()) return new BigDecimal("100.00");
        int matched = matched(jdTokens, resumeTokens).size();
        return BigDecimal.valueOf(matched)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(jdTokens.size()), 2, RoundingMode.HALF_UP);
    }

    @Override
    public List<String> matched(Set<String> jdTokens, Set<String> resumeTokens) {
        Set<String> resumeNorm = new java.util.HashSet<>();
        for (String t : resumeTokens) resumeNorm.add(normalizer.canonical(t));
        List<String> out = new ArrayList<>();
        for (String s : jdTokens) {
            String c = normalizer.canonical(s);
            if (resumeNorm.contains(c)) out.add(s);
        }
        return out;
    }

    @Override
    public List<String> partialMatched(Set<String> jdTokens, Set<String> resumeTokens) {
        return List.of();
    }

    @Override
    public List<String> missing(Set<String> jdTokens, Set<String> resumeTokens) {
        List<String> matched = matched(jdTokens, resumeTokens);
        List<String> out = new ArrayList<>();
        for (String s : jdTokens) if (!matched.contains(s)) out.add(s);
        return out;
    }
}
