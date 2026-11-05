/**
 * 职业资料库：可复用的职业事实条目，是简历内容的**唯一素材来源**。
 *
 * <h2>为什么它不是"简历的一个字段"</h2>
 * 同一段工作经历会被不同岗位的简历反复取用。把素材独立成库，才能做到「改一次、所有简历受益」，
 * 也才能回答「这句话是从哪条资料来的」——溯源链路的起点就在这里。
 *
 * <h2>三类资料</h2>
 * <ul>
 *   <li><b>基础资料</b>：工作、项目、教育、技能、证书、语言等；</li>
 *   <li><b>深化资料</b>：量化成果、管理/协作经历、单技能证据 —— 必须关联到工作或项目才有上下文；</li>
 *   <li><b>使用偏好</b>：{@code PREFERRED} 提高选材优先级；{@code EXCLUDED} 默认<b>不发送给模型</b>，
 *       但用户本次手动必选可以覆盖它，且<b>不改变全局偏好</b>。</li>
 * </ul>
 *
 * <h2>两条实现约束</h2>
 * <ul>
 *   <li>{@code contentJson} 走<b>最小 schema 软校验</b>（{@code CareerMaterialSchema}）：
 *       缺字段给提示而不是硬拒，避免把历史数据锁死；</li>
 *   <li>发给模型前必须经 {@code CareerMaterialAiSnapshotSanitizer} 脱敏，并受
 *       {@code app.ai.prompt.*} 的字节预算约束（超限截断并标 {@code [truncated]}，不丢资料）。</li>
 * </ul>
 */
package com.intelligentresume.careermaterial;
