package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认保存草稿请求。仅持久化 communication_draft，绝不触发任何发送。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code templateId} 可空：传了才校验模板可见性并给该模板 usageCount+1；不传就是纯手工草稿。</li>
 *   <li>{@code draftText} 只做非空校验，长度上限由 DDL（TEXT）承担，不在 DTO 层截断。</li>
 *   <li>服务端不校验正文与简历是否一致 —— 用户手动改过的文案允许与素材有出入。</li>
 * </ul>
 */
public record SaveDraftRequest(
        @NotNull Long resumeVersionId,
        @NotNull Long jobDescriptionId,
        @NotNull CommunicationType type,
        @NotBlank String draftText,
        Long templateId
) {}
