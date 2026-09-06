package com.intelligentresume.scoring.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 规则注册表：持有各规则实例与权重配置。
 */
@Component
public class RuleRegistry {

    private final KeywordRule keywordRule;
    private final SkillRule skillRule;
    private final ExperienceRule experienceRule;

    private final Map<String, BigDecimal> weights;

    public RuleRegistry(KeywordRule keywordRule,
                        SkillRule skillRule,
                        ExperienceRule experienceRule,
                        @Value("${app.scoring.weights.keyword:0.4}") double keywordWeight,
                        @Value("${app.scoring.weights.skill:0.4}") double skillWeight,
                        @Value("${app.scoring.weights.experience:0.2}") double experienceWeight) {
        this.keywordRule = keywordRule;
        this.skillRule = skillRule;
        this.experienceRule = experienceRule;
        this.weights = Map.of(
                keywordRule.name(), validateWeight(keywordRule.name(), keywordWeight),
                skillRule.name(), validateWeight(skillRule.name(), skillWeight),
                experienceRule.name(), validateWeight(experienceRule.name(), experienceWeight));
        BigDecimal total = this.weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (BigDecimal.ONE.compareTo(total) != 0) {
            throw new IllegalArgumentException("Scoring rule weights must sum to 1.0, got " + total);
        }
    }

    public KeywordRule keywordRule() { return keywordRule; }
    public SkillRule skillRule() { return skillRule; }
    public ExperienceRule experienceRule() { return experienceRule; }
    public BigDecimal keywordWeight() { return weights.get("keyword"); }
    public BigDecimal skillWeight() { return weights.get("skill"); }
    public BigDecimal experienceWeight() { return weights.get("experience"); }
    public Map<String, BigDecimal> weights() { return weights; }

    /**
     * Aggregate scores only when every registered rule has produced a score.
     * A new rule therefore cannot silently disappear from the total by being
     * omitted from the service's result map.
     */
    public BigDecimal weightedTotal(Map<String, BigDecimal> scores) {
        if (!weights.keySet().equals(scores.keySet())) {
            throw new IllegalArgumentException("Scoring rule result set does not match registry: "
                    + scores.keySet() + " vs " + weights.keySet());
        }
        return weights.entrySet().stream()
                .map(entry -> entry.getValue().multiply(scores.get(entry.getKey())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal validateWeight(String name, double value) {
        BigDecimal weight = BigDecimal.valueOf(value);
        if (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Scoring rule weight must be between 0 and 1: " + name);
        }
        return weight;
    }
}
