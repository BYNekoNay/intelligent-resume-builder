package com.intelligentresume.ai.material.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record MaterialResumeGenerationRequest(@NotBlank @Size(max = 30000) String rawMaterialText, Long jobDescriptionId) { }
