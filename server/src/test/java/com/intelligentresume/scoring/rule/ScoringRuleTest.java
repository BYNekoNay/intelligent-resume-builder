package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringRuleTest {

    private final KeywordRule keywordRule = new KeywordRule(new Normalizer());
    private final SkillRule skillRule = new SkillRule(new Normalizer());

    @Test
    void emptyJobKeywordsAreFullyCovered() {
        Set<String> noJobKeywords = Set.of();
        Set<String> resumeTokens = Set.of("java", "spring");

        assertThat(keywordRule.score(noJobKeywords, resumeTokens, Map.of()))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(skillRule.score(noJobKeywords, resumeTokens, Map.of()))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
