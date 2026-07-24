package com.intelligentresume.scoring.rule;

/**
 * 规则版本常量。每次评分写入 match_result.rule_version，
 * 必须与 {@code app.scoring.rule-version} 配置一致。
 */
public final class RuleVersion {

    public static final String CURRENT = "v1.0.0";

    private RuleVersion() {}
}
