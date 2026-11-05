package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能规则：与 KeywordRule 逻辑相同，但仅针对简历 skills 子集 token。
 * 命中规则更严格——使用相同的归一化 + 同义词机制。
 *
 * <p>要点：
 * <ul>
 *   <li><b>没有任何独立逻辑</b>：完全委托 {@code keywordRule.evaluate}，本类只提供不同的 token 来源与规则名 {@code "skill"}。
 *       要改算法请改 KeywordRule，并意识到 keyword 维度会一起变。</li>
 *   <li>skills 子集的抽取范围由 {@code ResumeKeywordExtractor} 决定（含 {@code name} / {@code items} / {@code keywords}），
 *       抽不出来会直接表现为该维度偏低。</li>
 *   <li>规则名 {@code "skill"} 是权重表的键，不能改。</li>
 * </ul>
 */
@Component
public class SkillRule {

    private final KeywordRule keywordRule;

    public SkillRule(KeywordRule keywordRule) {
        this.keywordRule = keywordRule;
    }

    public String name() {
        return "skill";
    }

    /**
     * 评估技能覆盖度（使用简历 skills 子集 token）。
     */
    public KeywordRule.RuleResult evaluate(List<String> jdKeywords, Set<String> skillTokens,
                                            Set<String> skillRawTokens) {
        return keywordRule.evaluate(jdKeywords, skillTokens, skillRawTokens);
    }
}
