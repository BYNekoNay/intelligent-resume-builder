package com.intelligentresume.auth.dto;

/**
 * 当前用户信息（{@code GET /api/auth/me} 与资料更新的返回值）。
 *
 * <p>刻意只含这四个字段：不含 {@code passwordHash}、不含状态与删除标记——响应会被前端缓存与展示，
 * 增加字段前先确认它不是凭据或内部状态。
 */
public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        String displayName
) {}