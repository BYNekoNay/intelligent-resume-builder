package com.intelligentresume.export.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExportRequest(
        @NotNull Long resumeVersionId,
        @NotBlank String templateCode
) {}