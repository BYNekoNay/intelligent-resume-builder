package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 拒绝请求。
 *
 * <p>一句人话：确认页"整单拒绝"按钮的载荷——只有一个乐观锁时间戳，
 * 证明用户是基于最新页面点的拒绝。
 *
 * @param taskUpdatedAt 乐观锁：必须与 ai_task.updated_at 一致，否则 40901
 */
public record RejectRequest(
        @NotNull LocalDateTime taskUpdatedAt
) {}
