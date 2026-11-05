package com.intelligentresume.jobdescription.service;

/**
 * JD 解析器规则版本常量。
 *
 * <p>每次解析结果与版本号一起写入 {@code parsed_keywords_json} 和 {@code parsed_version},
 * 便于 T09 复用与回溯。
 *
 * <p>要点：
 * <ul>
 *   <li>同一个版本号被写进两个地方（JSON 内 {@code version} 字段 + 独立列
 *       {@code parsed_version}），改写入逻辑时必须两处同改，否则两份记录会不一致；</li>
 *   <li>后端目前只写不读：没有"版本落后即视为过期并重解析"的判定，
 *       是否重解析由用户显式触发；</li>
 *   <li>版本号是字符串常量而非枚举，前端/导出可能原样展示，不要改成数字或带构建号；</li>
 *   <li>升级版本不会自动回填历史行，历史 {@code parsed_keywords_json} 仍是旧口径。</li>
 * </ul>
 */
public final class JdParserRuleVersion {

    /** 当前生效的规则版本；解析器每次落库都写这个值。 */
    public static final String CURRENT = "v1.0.0";

    private JdParserRuleVersion() {}
}
