package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Optional, server-validated provenance for an ATS-driven version successor.
 *
 * <p>恢复版本时可选携带的 ATS 溯源信息（服务端逐项校验，不信任客户端）：
 * <ul>
 *     <li>{@code atsResultId}：ATS 分析结果 id，必须属于当前用户，且其 {@code resumeVersionId}
 *         必须等于被恢复的版本。</li>
 *     <li>{@code atsItem}：格式固定为 {@code evidence:<下标>} 或 {@code action:<下标>}，下标从 0 开始、不允许前导零。</li>
 *     <li>任何一项校验不过，恢复就不会发生——不存在"恢复成功但溯源丢了"的中间态。</li>
 * </ul>
 */
public record RestoreResumeVersionRequest(
        @NotNull Long atsResultId,
        @NotBlank String atsItem
) {
}
