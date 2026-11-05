package com.intelligentresume.application.domain;

/**
 * 投递状态（9 态）。
 *
 * <h2>主链与旁支</h2>
 * 主链：{@link #DRAFT} → {@link #APPLIED} → {@link #SCREENING} → {@link #INTERVIEWING} →
 * {@link #OFFERED} → {@link #HIRED}。三个旁支（{@link #REJECTED}、{@link #WITHDRAWN}、
 * {@link #NO_RESPONSE}）可以从主链任意环节进入，其中 {@code NO_RESPONSE} 是唯一可被重新打开的。
 *
 * <h2>枚举顺序不是流程顺序</h2>
 * 这里的声明顺序只用于稳定序列化，**不要**拿 {@code ordinal()} 推流程：
 * 合法的边全部写在 {@code ApplicationService#TRANSITIONS} 的白名单里，那张表才是唯一判据。
 *
 * <h2>加字段/加状态时要同步三处</h2>
 * 后端本枚举、前端状态数组、{@code docs/04-数据库设计说明书.md} 的表格由门禁逐格比对；
 * 只改一处会出现「后端能迁移、前端画不出」或「文档说能走、代码拒绝」的静默不一致。
 *
 * <p>语义边界：本枚举描述的是<b>用户的投递进展</b>，不是 {@code ai_task} 或 {@code export_task}
 * 的任务状态 —— 三者不要混用同一套字符串。
 */
public enum ApplicationStatus {
    /** 草稿：尚未投递。初始状态，也是唯一允许改绑 JD / 简历版本的阶段。 */
    DRAFT,
    /** 已投递：首次进入时写入 {@code appliedAt}，之后不再改动（它是「投了多久」的起点）。 */
    APPLIED,
    /** 筛选中：简历在 HR 或系统筛选环节。 */
    SCREENING,
    /** 面试中：已进入面试轮次（与模拟面试无关，这里指真实求职流程）。 */
    INTERVIEWING,
    /** 已发 offer：等待答复或已接受，尚未入职。 */
    OFFERED,
    /** 已入职：终态，只能自环。需要再投同一家时应新建记录，而不是把历史改回去。 */
    HIRED,
    /** 被拒：终态，只能自环。 */
    REJECTED,
    /** 已撤回：终态，只能自环（用户主动放弃）。 */
    WITHDRAWN,
    /** 无回应：准终态。雇主后来回复时可以一步走到筛选中/面试中，不必伪造一次「重新投递」。 */
    NO_RESPONSE
}
