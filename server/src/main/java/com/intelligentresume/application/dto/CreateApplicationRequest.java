package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建投递记录请求。
 *
 * <p>{@code status} 可空，但只允许 DRAFT：传其它值服务层会以 409 拒绝——投递必须从草稿开始，
 * 首次真实投递要走状态迁移接口，才能写入 appliedAt 与历史。
 */
public record CreateApplicationRequest(
        @NotNull Long jobDescriptionId,
        @NotNull Long resumeVersionId,
        ApplicationStatus status,
        @Size(max = 30000) String coverLetterText,
        @Size(max = 30000) String emailBodyText,
        @Size(max = 30000) String openingMessageText,
        LocalDateTime nextFollowUpAt
) {}
