package com.intelligentresume.scoring.rule;

/**
 * 规则版本常量。每次评分写入 match_result.rule_version，
 * 必须与 {@code app.scoring.rule-version} 配置一致。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code ScoringService} 的注解默认值编译期拼接本常量
 *       {@code @Value("${app.scoring.rule-version:" + RuleVersion.CURRENT + "}")}（#78）：
 *       本常量是版本号的唯一真源 —— 改版本只改这里，配置键可在 yml 覆盖。</li>
 *   <li>版本号参与结果复用判定：改了规则逻辑却不改版本，旧的等价结果会被继续复用。</li>
 *   <li>{@code match_result.rule_version} 是 {@code varchar(32)}，取值不要超长。</li>
 * </ul>
 */
public final class RuleVersion {

    public static final String CURRENT = "v1.0.0";

    private RuleVersion() {}
}
