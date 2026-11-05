package com.intelligentresume.careermaterial.domain;

/**
 * 职业资料类型。
 *
 * <p>包含 T04 手册定义的 6 种类型 + 前端已实现的 AWARD 类型。
 *
 * <p>要点：
 * <ul>
 *   <li><b>枚举名即入库值</b>：{@code @Enumerated(EnumType.STRING)} 直接写
 *       {@code career_material.material_type}（varchar 32），改名等于改数据，历史行会读不出来；</li>
 *   <li>声明顺序不参与持久化，也不保证对外顺序；需要展示顺序时由前端/接口显式定义；</li>
 *   <li>前 6 项（WORK_EXPERIENCE … HIGHLIGHT）有手册口径，其余为后续新增，
 *       新增类型要同步三处：{@link com.intelligentresume.careermaterial.service.CareerMaterialSchema}
 *       的 schema 目录、前端的类型字典、以及详情/摘要的 excerpt 取键分支；</li>
 *   <li>只有 ACHIEVEMENT / LEADERSHIP_EXPERIENCE / SKILL_EVIDENCE 三类有硬校验，
 *       其余走软校验，所以新增类型即便不补 schema 也能写入，只是拿不到告警数据。</li>
 * </ul>
 */
public enum MaterialType {
    WORK_EXPERIENCE,
    PROJECT_EXPERIENCE,
    SKILL,
    EDUCATION,
    CERTIFICATE,
    HIGHLIGHT,
    AWARD,
    ACHIEVEMENT,
    LEADERSHIP_EXPERIENCE,
    SKILL_EVIDENCE,
    VOLUNTEER_EXPERIENCE,
    COURSE,
    PUBLICATION
}
