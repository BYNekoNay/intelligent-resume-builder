package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求，也被 {@code /api/auth/deletion/restore} 复用。
 *
 * <p>{@code username} 字段同时接受用户名或邮箱：AuthService 先按用户名查、查不到再按邮箱查；
 * 两处失败统一报「账号或密码错误」，避免账号枚举。这里的 {@code @Size} 只是格式防线，不是密码强度策略。
 */
public record LoginRequest(
        @NotBlank
        @Size(max = 128)
        String username,

        @NotBlank
        @Size(min = 8, max = 128)
        String password
) {}