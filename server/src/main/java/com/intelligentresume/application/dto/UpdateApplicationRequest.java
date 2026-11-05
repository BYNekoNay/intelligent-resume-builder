package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 更新投递记录请求（不含状态迁移）。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code status} 只允许为空或与当前值相同；想改状态必须走状态迁移接口。</li>
 *     <li>改绑 JD / 简历版本只在 DRAFT 且尚未投递（{@code appliedAt} 为空）时允许，否则 409。</li>
 *     <li>{@code version} 必填：与库里的乐观锁版本不一致会以 409 拒绝（防覆盖并发修改）。</li>
 * </ul>
 */
public record UpdateApplicationRequest(
        @NotNull Long jobDescriptionId,
        @NotNull Long resumeVersionId,
        ApplicationStatus status,
        @Size(max = 30000) String coverLetterText,
        @Size(max = 30000) String emailBodyText,
        @Size(max = 30000) String openingMessageText,
        @NotNull Long version,
        LocalDateTime nextFollowUpAt
) {}
