package com.intelligentresume.auth.dto;

public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        String displayName
) {}