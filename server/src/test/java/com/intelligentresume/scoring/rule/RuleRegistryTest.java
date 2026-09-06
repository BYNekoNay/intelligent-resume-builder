package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleRegistryTest {

    @Test
    void aggregatesOnlyRegisteredRuleScores() {
        KeywordRule keyword = mock(KeywordRule.class);
        SkillRule skill = mock(SkillRule.class);
        ExperienceRule experience = mock(ExperienceRule.class);
        when(keyword.name()).thenReturn("keyword");
        when(skill.name()).thenReturn("skill");
        when(experience.name()).thenReturn("experience");
        RuleRegistry registry = new RuleRegistry(keyword, skill, experience, 0.4, 0.4, 0.2);

        assertEquals(new BigDecimal("75.0"), registry.weightedTotal(Map.of(
                "keyword", new BigDecimal("100"),
                "skill", new BigDecimal("50"),
                "experience", new BigDecimal("75"))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.weightedTotal(Map.of("keyword", BigDecimal.ONE)));
    }

    @Test
    void rejectsInvalidWeightConfiguration() {
        KeywordRule keyword = mock(KeywordRule.class);
        SkillRule skill = mock(SkillRule.class);
        ExperienceRule experience = mock(ExperienceRule.class);
        when(keyword.name()).thenReturn("keyword");
        when(skill.name()).thenReturn("skill");
        when(experience.name()).thenReturn("experience");

        assertThrows(IllegalArgumentException.class,
                () -> new RuleRegistry(keyword, skill, experience, 0.5, 0.5, 0.5));
    }
}
