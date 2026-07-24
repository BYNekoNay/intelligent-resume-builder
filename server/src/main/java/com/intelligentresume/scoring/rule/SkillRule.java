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
