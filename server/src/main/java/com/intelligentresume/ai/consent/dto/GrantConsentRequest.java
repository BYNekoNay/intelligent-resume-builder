package com.intelligentresume.ai.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 授权 AI 数据处理请求。
 *
 * <p>要点:
 * <ul>
 *   <li>{@code policyVersion} 必须与当前配置完全一致,否则 VALIDATION(说明用户看的是旧协议)。</li>
 *   <li>{@code providerCode} 目前只接受 {@code bailian}。</li>
 *   <li>{@code taskScopes} 用任务类型名,{@code dataCategories} 用数据类别名,两者都会被原样存成 JSON 数组。</li>
 * </ul>
 */
public record GrantConsentRequest(
        @NotBlank String policyVersion,
        @NotBlank String providerCode,
        @NotNull List<String> taskScopes,
        @NotNull List<String> dataCategories,
        @NotBlank String noticeHash
) {
}
