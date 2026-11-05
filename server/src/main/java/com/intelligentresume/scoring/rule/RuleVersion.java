package com.intelligentresume.scoring.rule;

/**
 * 规则版本常量。每次评分写入 match_result.rule_version，
 * 必须与 {@code app.scoring.rule-version} 配置一致。
 *
 * <p>要点：
 * <ul>
 *   <li>真正写库的值来自 {@code ScoringService} 的 {@code @Value("${app.scoring.rule-version:v1.0.0}")}，
 *       本常量目前<b>没有任何代码引用</b>，只是文档性声明 —— 改版本时必须两边一起改，否则会出现
 *       「常量说 v1.1.0、库里仍是 v1.0.0」的漂移。</li>
 *   <li>版本号参与结果复用判定：改了规则逻辑却不改版本，旧的等价结果会被继续复用。</li>
 *   <li>{@code match_result.rule_version} 是 {@code varchar(32)}，取值不要超长。</li>
 * </ul>
 */
public final class RuleVersion {

    public static final String CURRENT = "v1.0.0";

    private RuleVersion() {}
}
