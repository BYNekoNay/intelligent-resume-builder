package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改邮箱请求：必须携带当前密码（防止被盗 token 直接换绑邮箱），新邮箱按 {@code @Email} 校验。
 * 成功后会撤销全部刷新会话，与改密同强度。
 */
public record ChangeEmailRequest(
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(min = 8, max = 128) String currentPassword
) {}
