package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Optional, server-validated provenance for an ATS-driven version successor. */
public record RestoreResumeVersionRequest(
        @NotNull Long atsResultId,
        @NotBlank String atsItem
) {
}
