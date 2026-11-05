package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改密请求：{@code currentPassword} 用于证明本人（失败统一 401），{@code newPassword} 同样受 8~128 长度约束。
 * 成功后服务端会撤销该用户全部刷新会话，所有设备需重新登录。
 */
public record ChangePasswordRequest(
        @NotBlank @Size(min = 8, max = 128) String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword
) {}
