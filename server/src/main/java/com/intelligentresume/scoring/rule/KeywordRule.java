package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词匹配规则。
 *
 * <p>score = matched / (matched + partial + missing)。
 * 语义:只匹配规范化的 jdTokens,resumeTokens 任意命中算 matched;同义词归一化一致则算 fully matched。
 */
@Component
public class KeywordRule implements ScoringRule {

    private final Normalizer normalizer;

    public KeywordRule(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public String name() { return "keyword"; }

    @Override
    public BigDecimal score(Set<String> jdTokens, Set<String> resumeTokens, Map<String, Object> jdMeta) {
        if (jdTokens.isEmpty()) return new BigDecimal("100.00");
        Set<String> normResume = canonicalize(resumeTokens);
        Set<String> normJd = canonicalize(jdTokens);
        int matched = 0;
        int partial = 0;
        for (String jd : normJd) {
            if (normResume.contains(jd)) matched++;
            else if (partialMatch(jd, normResume)) partial++;
        }
        int missing = Math.max(0, normJd.size() - matched - partial);
        int total = Math.max(1, matched + partial + missing);
        // 部分匹配按 0.5 折算
        BigDecimal raw = BigDecimal.valueOf(matched + partial * 0.5)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        return raw.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public List<String> matched(Set<String> jdTokens, Set<String> resumeTokens) {
        Set<String> normJd = canonicalize(jdTokens);
        Set<String> normResume = canonicalize(resumeTokens);
        List<String> r = new ArrayList<>();
        for (String s : normJd) if (normResume.contains(s)) r.add(s);
        return r;
    }

    @Override
    public List<String> partialMatched(Set<String> jdTokens, Set<String> resumeTokens) {
        Set<String> normJd = canonicalize(jdTokens);
        Set<String> normResume = canonicalize(resumeTokens);
        List<String> r = new ArrayList<>();
        for (String s : normJd) {
            if (!normResume.contains(s) && partialMatch(s, normResume)) r.add(s);
        }
        return r;
    }

    @Override
    public List<String> missing(Set<String> jdTokens, Set<String> resumeTokens) {
        Set<String> normJd = canonicalize(jdTokens);
        Set<String> normResume = canonicalize(resumeTokens);
        List<String> r = new ArrayList<>();
        for (String s : normJd) {
            if (!normResume.contains(s) && !partialMatch(s, normResume)) r.add(s);
        }
        return r;
    }

    private Set<String> canonicalize(Set<String> tokens) {
        Set<String> out = new HashSet<>();
        for (String t : tokens) out.add(normalizer.canonical(t));
        return out;
    }

    private boolean partialMatch(String jdToken, Set<String> resumeCanonical) {
        // 任一 resume token 命中 jdToken 的子串/前缀,或长度差不超 1 的编辑距离近似
        for (String rt : resumeCanonical) {
            if (jdToken.length() >= 4 && (rt.contains(jdToken) || jdToken.contains(rt))) return true;
            if (Math.abs(jdToken.length() - rt.length()) <= 1 && closeEnough(jdToken, rt)) return true;
        }
        return false;
    }

    private boolean closeEnough(String a, String b) {
        if (a.equals(b)) return true;
        int n = Math.abs(a.length() - b.length());
        if (n > 1) return false;
        int diff = 0;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) != b.charAt(j)) {
                diff++;
                if (diff > 1) return false;
                if (a.length() > b.length()) i++;
                else if (b.length() > a.length()) j++;
                else { i++; j++; }
            } else { i++; j++; }
        }
        if (i < a.length() || j < b.length()) diff++;
        return diff <= 1;
    }
}
