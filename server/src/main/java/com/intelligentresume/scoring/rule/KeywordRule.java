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
 *
 * <p>要点：
 * <ul>
 *   <li><b>三类互斥且并集 = 全部有效关键词</b>：归一化后为空的关键词被跳过，<b>不计入分母</b>
 *       （否则一个纯符号关键词就能白白拉低分数）。</li>
 *   <li><b>direct 与 synonym 的区分只影响明细</b>：{@code resumeRawTokens} 是未做同义词归一的集合，
 *       用它判断"是否逐字命中"；分数上两者等权。</li>
 *   <li><b>无关键词给 100 分</b>：JD 解析不出关键词时 keyword 维度不惩罚；改这个默认值会让所有空 JD 的分数突变。</li>
 *   <li>本规则被 {@link SkillRule} 复用（传入 skills 子集 token），改算法会同时改两个维度。</li>
 * </ul>
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
