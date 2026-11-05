package com.intelligentresume.ats.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 发起 ATS 检查的请求体。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code useAi} 为 null 时**按 true 处理**（见 {@link #shouldUseAi()}）——
 *       前端不传就是想用 AI，不要把它当"默认关闭"。</li>
 *   <li>{@code useAi=true} 也不保证真的会调模型：还要求配置可用 + 带预览令牌 + 有配额，
 *       任一不满足都会落成 RULES_FALLBACK。</li>
 *   <li>两个 ID 都由服务端做归属校验：他人的版本/岗位一律 404。</li>
 * </ul>
 */
public record AtsCheckRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId, Boolean useAi) {
    public boolean shouldUseAi() {
        return useAi == null || useAi;
    }
}
