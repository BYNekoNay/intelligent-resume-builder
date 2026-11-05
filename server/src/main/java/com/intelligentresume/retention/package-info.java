/**
 * 数据生命周期：分档清扫 + 账户删除。
 *
 * <h2>分档规则（决策 D2）</h2>
 * <ul>
 *   <li><b>A 档：无人引用</b> → 超过保留期后<b>物理删除</b>；</li>
 *   <li><b>B 档：仍被简历版本引用</b> → 转成<b>最小快照</b>，保留"曾经用过这条事实"的证据，
 *       而不是把用户简历背后的资料抽走；</li>
 *   <li>引用判据是 {@code resume_material_reference} —— 这也是为什么确认草稿时
 *       「少提交素材」必须报 {@code 40904}：少一条引用会让资料提前掉进删除档，
 *       <b>代码回滚救不回已删数据</b>（ADR-013）。</li>
 * </ul>
 *
 * <h2>默认是安全的</h2>
 * {@code enabled=false} + {@code dry-run=true} 是刻意的双护栏：部署后<b>不会删除任何数据</b>，
 * 必须先显式解除才会真正物理删除。线上建议先开 {@code dry-run} 观察两周再收口。
 *
 * <h2>覆盖范围</h2>
 * 六个资源：简历版本、职业资料、JD、面试资产，以及 V40 起纳入的投递记录与答案资产。
 *
 * <h2>账户侧</h2>
 * 删除账户不是立即硬删：先写 {@code account_deletion_job}，留 <b>7 天撤销窗口</b>，
 * 到期后按依赖顺序级联硬删。
 *
 * <p>方案与证据见 {@code docs/plans/2026-10-01-001-data-retention-tiered-purge.md}；
 * 口径统一在 {@code docs/04-数据库设计说明书.md} §7.1。
 */
package com.intelligentresume.retention;
