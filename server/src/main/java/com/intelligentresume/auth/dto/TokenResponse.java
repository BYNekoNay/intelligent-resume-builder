package com.intelligentresume.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 令牌响应：access token 给前端放内存/请求头，refresh token 只写 Cookie。
 *
 * <p>{@code refreshToken} 上的 {@code @JsonIgnore} 是安全边界：本记录会被直接序列化进响应体，
 * 去掉该注解即等于把 refresh token 暴露给 JS（HttpOnly Cookie 的意义随之消失）。
 */
public record TokenResponse(
        String accessToken,
        long accessTokenExpiresInSeconds,
        @JsonIgnore String refreshToken
) {}
