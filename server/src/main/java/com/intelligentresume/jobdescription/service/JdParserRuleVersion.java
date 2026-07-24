package com.intelligentresume.jobdescription.service;

/**
 * JD 解析器规则版本常量。
 *
 * <p>每次解析结果与版本号一起写入 {@code parsed_keywords_json} 和 {@code parsed_version},
 * 便于 T09 复用与回溯。
 */
public final class JdParserRuleVersion {

    public static final String CURRENT = "v1.0.0";

    private JdParserRuleVersion() {}
}
