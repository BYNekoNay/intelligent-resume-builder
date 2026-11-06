package com.intelligentresume.scoring.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 规则注册表：持有各规则实例与权重配置。
 *
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：总分不是三条规则分数的简单相加，而是按「关键词 0.4 +
 *       技能 0.4 + 经验 0.2」加权。本类是唯一持有这套权重的地方，并保证配置错了起不来服务。</li>
 *   <li><b>在链路哪里</b>：{@code ScoringService} 每次评分经它取规则实例和加权总分；
 *       权重来自 {@code app.scoring.weights.*} 配置。</li>
 *   <li><b>改坏它用户会看到什么</b>：加和校验去掉 → 配成 0.4/0.4/0.4 时总分上限变成 120 分，
 *       用户看到超过 100 的荒谬分数；weightedTotal 的键集合校验去掉 → 新增规则漏算时不报错、总分悄悄变。</li>
 * </ul>
 *
 * <p>要点：
 * <ul>
 *   <li><b>启动即校验</b>：每个权重必须落在 [0,1]，且三者之和必须精确等于 1.0，否则 Bean 创建失败
 *       （宁可起不来，也不要带着错权重静默出分）。</li>
 *   <li><b>weightedTotal 要求结果集合与注册表键集合完全相等</b>：新增规则时忘记在 service 里产出分数会直接抛错，
 *       不会悄悄少算一个维度。</li>
 *   <li>权重键固定为 {@code keyword} / {@code skill} / {@code experience}（来自各规则的 {@code name()}），
 *       与 {@code app.scoring.weights.*} 配置键一一对应。</li>
 *   <li>本类无状态，构造后只读；改权重只需改配置并重启，不要在运行时改 Map。</li>
 * </ul>
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
     * 只有当注册表里的每条规则都在结果映射中给出了分值时才聚合总分。
     * 这样新增一条规则后，如果服务层的结果映射漏掉它，不会静默地从总分里消失，而是直接报错。
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
