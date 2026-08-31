package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeEmailRequest(
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(min = 8, max = 128) String currentPassword
) {}
