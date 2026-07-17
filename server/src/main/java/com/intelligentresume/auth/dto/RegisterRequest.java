package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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