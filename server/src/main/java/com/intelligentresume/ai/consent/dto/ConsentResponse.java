package com.intelligentresume.ai.consent.dto;

import com.intelligentresume.ai.consent.domain.ConsentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同意状态响应。
 *
 * <p>要点:
 * <ul>
 *   <li>描述的是"最新一条事件",因此 {@code status} 为 WITHDRAWN 表示用户已撤回。</li>
 *   <li>字段名去掉 {@code Json} 后缀,与库列名({@code task_scopes_json})不同,不要互相照抄。</li>
 *   <li>不含任何提示文本原文,只有 {@code policyVersion} 与哈希。</li>
 * </ul>
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
