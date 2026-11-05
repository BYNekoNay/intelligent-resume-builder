/**
 * 投递闭环：9 态状态机、状态历史审计、跟进提醒与转化统计。
 *
 * <h2>三个概念先分清</h2>
 * <ul>
 *   <li>{@code ApplicationRecord} —— 投递主记录，绑定 JD 与简历版本；</li>
 *   <li>{@code ApplicationStatusHistory} —— 每次**真实迁移**写一条 {@code from/to/note/时间}，
 *       是「这条投递究竟发生过什么」的唯一证据；</li>
 *   <li>{@code stageEnteredAt} —— 当前阶段的进入时刻，**只能在状态真正变化时刷新**，
 *       它是「各阶段平均停留天数」的分子。</li>
 * </ul>
 *
 * <h2>为什么状态不能顺手改</h2>
 * 状态迁移只允许走 {@code ApplicationService#updateStatus}（白名单 + 乐观锁 + 写历史）。
 * 普通编辑接口 {@code update} 一旦收到与当前值不同的 status 就直接拒绝 ——
 * 否则「改个备注」会绕过历史表，审计时间线随即失真。
 *
 * <h2>删除是软的</h2>
 * V40 起删除只置 {@code deleted_at}，用户在保留期内可以恢复；物理删除由
 * {@code com.intelligentresume.retention.RetentionPurgeService} 在引用判定后执行。
 *
 * <p>统计算法在 {@code ApplicationStatsCalculator}（纯函数），本包不把口径散落在仓储查询里。
 */
package com.intelligentresume.application;
