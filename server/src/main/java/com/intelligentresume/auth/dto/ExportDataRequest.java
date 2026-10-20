package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExportDataRequest(@NotBlank @Size(min = 8, max = 128) String currentPassword) {}
