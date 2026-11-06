package com.intelligentresume.scoring.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 技能规则：与 KeywordRule 逻辑相同，但仅针对简历 skills 子集 token。
 * 命中规则更严格——使用相同的归一化 + 同义词机制。
 *
 * <p>小白三问：
 * <ul>
 *   <li><b>解决什么业务问题</b>：关键词覆盖度看的是整份简历（含项目描述、经历），
 *       但 HR 更看重「技能清单」一栏是否直接写了 JD 要求的技能。
 *       本维度只用简历 skills 部分的 token 再算一遍覆盖度，衡量技能区写得好不好。</li>
 *   <li><b>在链路哪里</b>：与 KeywordRule 同级，由 {@code ScoringService} 经
 *       {@link RuleRegistry} 调用；分数乘 0.4 权重进总分。</li>
 *   <li><b>改坏它用户会看到什么</b>：token 来源换成全简历 → 本维度与关键词维度分数几乎一样，
 *       失去独立意义；规则名 {@code "skill"} 改掉 → 权重表找不到键，启动即失败。</li>
 * </ul>
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
