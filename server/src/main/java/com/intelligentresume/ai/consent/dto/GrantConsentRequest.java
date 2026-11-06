package com.intelligentresume.ai.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 授权 AI 数据处理请求 —— 用户在授权弹窗里点"同意"时提交的内容。
 *
 * <p>{@code policyVersion} 必须与服务端当前配置一致（说明用户看的是最新协议）；
 * {@code providerCode} 目前只接受 {@code bailian}；{@code taskScopes} 用任务类型名、
 * {@code dataCategories} 用数据类别名，两者都会被原样存成 JSON 数组留证。</p>
 */
public record GrantConsentRequest(
        @NotBlank String policyVersion,
        @NotBlank String providerCode,
        @NotNull List<String> taskScopes,
        @NotNull List<String> dataCategories,
        @NotBlank String noticeHash
) {
}
