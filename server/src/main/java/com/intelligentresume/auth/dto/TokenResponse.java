package com.intelligentresume.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record TokenResponse(
        String accessToken,
        long accessTokenExpiresInSeconds,
        @JsonIgnore String refreshToken
) {}
