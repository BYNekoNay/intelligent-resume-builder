/**
 * 模拟面试：有限轮次问答、逐题评估、复盘报告与答案资产沉淀。
 *
 * <h2>读这一片的顺序</h2>
 * <ol>
 *   <li>{@code InterviewService} / {@code InterviewStartService} —— 会话生命周期与轮次上限；</li>
 *   <li>{@code InterviewRuleEngine} / {@code InterviewRuleService} —— 确定性部分（规则分、状态推进），
 *       与 AI 评估分离，保证模型不可用时仍有结论；</li>
 *   <li>{@code InterviewAiAttempt}（+ Repository）—— <b>幂等与失败重试的载体</b>；
 *       双层唯一约束 + 短事务 + 陈旧丢弃，决策见 ADR-010；</li>
 *   <li>{@code InterviewAnswerService} / {@code InterviewAssetService} —— 答案资产 CRUD 与筛选。</li>
 * </ol>
 *
 * <h2>作答接口是异步的</h2>
 * {@code /answer} 曾同步等待模型（实测 108.7s），现改为<b>秒回 + 轮询</b>，前端零改动沿用 taskId 轮询。
 * 连带把陈旧接管阈值从 75s 调到 600s —— 阈值必须大于单次模型调用的最坏耗时，否则正常长调用
 * 会被误判为"worker 已死"并重复接管。
 *
 * <h2>重试不重组输入</h2>
 * {@code ai-retry} 原地重跑<b>同一个 attempt</b>，不重建 prompt，因此<b>不要求</b>
 * {@code X-AI-Preview-Token}（决策 D8）。若将来改成重新组装输入，就必须纳入令牌并同步改文档。
 *
 * <p>上下文发送前经 {@code InterviewContextSanitizer} 脱敏；报告口径见
 * {@code docs/reviews/2026-07-28-ai-interview-implementation-report.md}（面试流程的权威结论）。
 */
package com.intelligentresume.interview;
