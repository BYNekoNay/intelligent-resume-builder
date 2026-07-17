package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(max = 128)
        String username,

        @NotBlank
        @Size(min = 8, max = 128)
        String password
) {}