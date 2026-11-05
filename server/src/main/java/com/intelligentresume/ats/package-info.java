/**
 * ATS 体检：规则评分与 AI 深度分析**双通道**，两者结果分开呈现。
 *
 * <h2>核心设计：AI 挂了不能变成空白</h2>
 * 规则通道（结构完整性、关键词覆盖）在本地完成，<b>不依赖模型可用性</b>。
 * AI 只负责岗位语义与证据强度的补充分析。因此失败时不是"检查失败"，而是
 * {@code RULES_FALLBACK} —— 规则结果照常返回，同时用 {@code AtsFallbackCode} /
 * {@code AtsFallbackInfo} 说清降级原因。
 *
 * <h2>发送前预览（R32）在 ATS 上的具体表现</h2>
 * {@code POST /api/ats/check} <b>不带</b> {@code X-AI-Preview-Token} 时**不再自动启动 AI**，
 * 直接落 {@code RULES_FALLBACK} + {@code PREVIEW_REQUIRED}。这是刻意的：
 * 体检会读取简历正文，用户有权先看到"将要发给模型的确切内容"。
 *
 * <h2>异步</h2>
 * AI 分析由 {@code AtsAiStarter} 创建任务，前端拿 {@code taskId} 轮询；
 * 状态机见 {@code AtsAnalysisStatus}，结果落 {@code ats_check_result}（V22 幂等键）。
 *
 * <p>历史缺陷：关键词覆盖度曾因词典绑定失效 + 技能字段读错 + PDF 丢弃技能项三处同源问题
 * 被低估（20.0 → 40.0），复盘见 {@code docs/reviews/2026-09-25-ats-keyword-coverage-fix.md}。
 */
package com.intelligentresume.ats;
