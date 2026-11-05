/**
 * AI 能力域：同意授权、配额、任务状态机、Worker 领取、模型链调用，以及「选材 → 生成」两阶段。
 *
 * <h2>读这一片的顺序</h2>
 * <ol>
 *   <li>{@code consent} —— 没有用户显式授权，资料不得发往模型（{@code AiConsentService}）；</li>
 *   <li>{@code task.domain.AiTask} —— 任务状态与租约字段，注释解释了它为什么不继承 {@code BaseEntity}；</li>
 *   <li>{@code task.service} —— 幂等键 + 内容指纹（{@code IdempotencyService}）、
 *       能力注册表（{@code AiTaskCapabilityRegistry}）、每日配额（{@code AiQuotaService}）；</li>
 *   <li>{@code worker.DatabaseTaskWorker} —— 领取、续租、重试与崩溃收口；</li>
 *   <li>{@code provider} —— 模型链的候选顺序与冷却（{@code ModelChainState}）、
 *       失败分类与顺延（{@code BailianFailureClassifier}）、提示词模板（{@code PromptTemplates}）。</li>
 * </ol>
 *
 * <h2>四条不能破的约定</h2>
 * <ul>
 *   <li><b>创建即 202 + taskId</b>：前端必须轮询 {@code /api/ai/tasks/{id}}，不能把创建响应当结果；</li>
 *   <li><b>发送前预览</b>：五个领域的创建端点要求 {@code X-AI-Preview-Token}，缺失返回 {@code 40001}；
 *       失败后的重试端点**不要求**令牌，因为它们重跑的是已落库的输入快照；</li>
 *   <li><b>不自动落库</b>：AI 只产出草稿/候选，必须经用户确认才写进简历版本；</li>
 *   <li><b>不落日志</b>：Prompt 原文、模型原文、用户资料、联系方式、Token、用户 ID
 *       都不得进日志、指标标签、测试报告或 Git。</li>
 * </ul>
 *
 * <p>产品边界见 {@code PROJECT_CONTEXT.md} §3「AI 与隐私边界」；决策背景见
 * {@code docs/decisions/ADR-001..006、009、013}。
 */
package com.intelligentresume.ai;
