package com.intelligentresume.careermaterial.domain;

/**
 * 资料使用偏好：用户给素材贴的"AI 选材时怎么对待它"的标记——
 * NORMAL(默认，正常参与) / PREFERRED(优先) / EXCLUDED(排除)。
 *
 * <p>要点：
 * <ul>
 *   <li>这是<b>全局偏好</b>，影响所有岗位的选材，不是某份简历的局部设置；</li>
 *   <li>{@code NORMAL} 是创建时的缺省值：请求体不带 usagePreference 时由
 *       {@code CareerMaterialService.create} 兜底，不要依赖数据库默认；</li>
 *   <li>{@code EXCLUDED} 的语义是"默认不发送给模型"，不是删除，资料仍在库里、
 *       仍可被列表与搜索查到；</li>
 *   <li>用户某次简历里<b>手动必选</b>可以覆盖 {@code EXCLUDED}，且此覆盖是单次行为，
 *       不得把它回写成 NORMAL/PREFERRED —— 否则一次临时选择会永久改变全局偏好；</li>
 *   <li>枚举名直接入库（varchar 16），改名等同数据迁移。</li>
 * </ul>
 */
public enum UsagePreference {
    NORMAL, PREFERRED, EXCLUDED
}
