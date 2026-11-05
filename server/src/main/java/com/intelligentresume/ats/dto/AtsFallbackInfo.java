package com.intelligentresume.ats.dto;

/**
 * 兜底详情：只描述"AI 为什么没跑成"，规则结果本身仍然有效。
 *
 * <ul>
 *   <li>{@code code} —— 原因码，前端按码映射文案（{@code AtsCheckView} 里有一张码→文案的表）。</li>
 *   <li>{@code message} —— 服务端给出的兜底说明；前端可以选择直接展示，但按码映射更稳。</li>
 *   <li>{@code retryable} —— 是否值得再点一次"重试 AI"。</li>
 *   <li>{@code consentRequired} —— 是否要先补授权（此时重试也没用，得先走授权流程）。</li>
 * </ul>
 *
 * <p>{@code null} 表示没有发生过降级（例如用户本来就没要求 AI，或 AI 已成功）。
 */
public record AtsFallbackInfo(
        AtsFallbackCode code,
        String message,
        boolean retryable,
        boolean consentRequired
) {
}
