package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 拒绝请求。
 *
 * @param taskUpdatedAt 乐观锁：必须与 ai_task.updated_at 一致，否则 40901
 */
public record RejectRequest(
        @NotNull LocalDateTime taskUpdatedAt
) {}
