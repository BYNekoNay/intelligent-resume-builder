package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认保存草稿请求。仅持久化 communication_draft，绝不触发任何发送。
 */
public record SaveDraftRequest(
        @NotNull Long resumeVersionId,
        @NotNull Long jobDescriptionId,
        @NotNull CommunicationType type,
        @NotBlank String draftText,
        Long templateId
) {}
