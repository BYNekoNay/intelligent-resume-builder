package com.intelligentresume.scoring.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 规则注册表：持有各规则实例与权重配置。
 */
@Component
public class RuleRegistry {

    private final KeywordRule keywordRule;
    private final SkillRule skillRule;
    private final ExperienceRule experienceRule;

    private final BigDecimal keywordWeight;
    private final BigDecimal skillWeight;
    private final BigDecimal experienceWeight;

    public RuleRegistry(KeywordRule keywordRule,
                        SkillRule skillRule,
                        ExperienceRule experienceRule,
                        @Value("${app.scoring.weights.keyword:0.4}") double keywordWeight,
                        @Value("${app.scoring.weights.skill:0.4}") double skillWeight,
                        @Value("${app.scoring.weights.experience:0.2}") double experienceWeight) {
        this.keywordRule = keywordRule;
        this.skillRule = skillRule;
        this.experienceRule = experienceRule;
        this.keywordWeight = BigDecimal.valueOf(keywordWeight);
        this.skillWeight = BigDecimal.valueOf(skillWeight);
        this.experienceWeight = BigDecimal.valueOf(experienceWeight);
    }

    public KeywordRule keywordRule() { return keywordRule; }
    public SkillRule skillRule() { return skillRule; }
    public ExperienceRule experienceRule() { return experienceRule; }
    public BigDecimal keywordWeight() { return keywordWeight; }
    public BigDecimal skillWeight() { return skillWeight; }
    public BigDecimal experienceWeight() { return experienceWeight; }
}
