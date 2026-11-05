package com.intelligentresume.ats.dto;

/**
 * 一次 ATS 检查的 AI 分析状态（存在 {@code result_json.analysisStatus}）。
 *
 * <p>流转：
 * <ul>
 *   <li>{@link #RULES_ONLY} —— 请求里明确不带 AI（{@code useAi=false}），只有规则结果，终态。</li>
 *   <li>{@link #RULES_FALLBACK} —— 想要 AI 但没跑成（未预览 / 未授权 / 配额 / provider 失败），
 *       终态；具体原因看 {@code fallback.code}。可以作为重试的起点。</li>
 *   <li>{@link #ANALYZING} —— AI 任务已创建，等待 worker 回写，非终态。</li>
 *   <li>{@link #COMPLETED} —— AI 结果已合并，终态；只有这个状态对应 {@code analysisSource=HYBRID}。</li>
 * </ul>
 *
 * <p>注意：规则分在任何状态下都存在，状态只描述"AI 那部分"。
 */
public enum AtsAnalysisStatus {
    ANALYZING,
    COMPLETED,
    RULES_ONLY,
    RULES_FALLBACK
}
