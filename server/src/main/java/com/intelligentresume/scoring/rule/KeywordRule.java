package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 关键词规则：计算 JD 关键词在简历中的覆盖度。
 *
 * <p>分类：
 * <ul>
 *   <li>matched：JD 关键词（归一化后）直接出现在简历 token 中</li>
 *   <li>partialMatched：通过同义词命中</li>
 *   <li>missing：JD 中存在但简历中无</li>
 * </ul>
 *
 * <p>分数 = (matched + partialMatched) / total * 100；无关键词时满分 100。
 * 关键词堆砌（重复出现多次）不影响分数（使用 Set 去重）。
 */
@Component
public class KeywordRule {

    private final Normalizer normalizer;

    public KeywordRule(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    public String name() {
        return "keyword";
    }

    /**
     * 评估关键词覆盖度。
     *
     * @param jdKeywords       JD 关键词列表（原始形式）
     * @param resumeTokens     简历归一化 token 集合
     * @param resumeRawTokens  简历原始 token 集合（小写去标点）
     * @return 评估结果
     */
    public RuleResult evaluate(List<String> jdKeywords, Set<String> resumeTokens,
                               Set<String> resumeRawTokens) {
        if (jdKeywords == null || jdKeywords.isEmpty()) {
            return new RuleResult(BigDecimal.valueOf(100), List.of(), List.of(), List.of());
        }

        List<String> matched = new ArrayList<>();
        List<String> partialMatched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        // 去重（防止 JD 关键词重复）
        LinkedHashSet<String> uniqueKeywords = new LinkedHashSet<>(jdKeywords);

        for (String keyword : uniqueKeywords) {
            String normalized = normalizer.normalize(keyword);
            if (normalized.isEmpty()) {
                continue;
            }
            if (resumeTokens.contains(normalized)) {
                // 判断是直接匹配还是同义词匹配
                if (normalizer.isDirectMatch(keyword, resumeRawTokens)) {
                    matched.add(keyword);
                } else {
                    partialMatched.add(keyword);
                }
            } else {
                missing.add(keyword);
            }
        }

        int total = matched.size() + partialMatched.size() + missing.size();
        BigDecimal score = total == 0
                ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf((matched.size() + partialMatched.size()) * 100.0 / total)
                    .setScale(2, RoundingMode.HALF_UP);

        return new RuleResult(score, matched, partialMatched, missing);
    }

    /**
     * 规则评估结果。
     */
    public record RuleResult(
            BigDecimal score,
            List<String> matched,
            List<String> partialMatched,
            List<String> missing
    ) {}
}
