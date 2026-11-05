package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>字段约束必须与 {@code User} 的列宽、前端表单校验一致：
 * 用户名 3~64 且只允许 {@code [a-zA-Z0-9_.-]}（DB 层长度 64），邮箱 ≤128，密码 8~128。
 * 改这里任何一个上限都要同步 User 列定义与前端校验，否则会出现「前端放行、DB 截断/报错」。
 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$")
        String username,

        @NotBlank
        @Email
        @Size(max = 128)
        String email,

        @NotBlank
        @Size(min = 8, max = 128)
        String password
) {}