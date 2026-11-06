package com.intelligentresume.ai.consent.dto;

import com.intelligentresume.ai.consent.domain.ConsentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同意状态响应 —— 返回给前端的"当前授权状态"（即最新一条事件的内容）。
 *
 * <p>{@code status} 为 WITHDRAWN 表示用户已撤回；不含任何提示文本原文，只有
 * {@code policyVersion} 与哈希。</p>
 */
public record ConsentResponse(
        Long id,
        String policyVersion,
        String providerCode,
        List<String> taskScopes,
        List<String> dataCategories,
        ConsentStatus status,
        LocalDateTime createdAt
) {
}
