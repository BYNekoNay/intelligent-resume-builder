package com.intelligentresume.ai.generation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 岗位定制生成请求。通过 CreateAiTaskRequest.input 传递,
 * 本 record 用于 JobGenerationService 内部解析。
 */
public record JobGenerationRequest(
        @NotNull Long targetResumeId,
        @NotNull Long jobDescriptionId,
        List<Long> includedMaterialIds,
        List<Long> preferredMaterialIds,
        List<Long> excludedMaterialIds
) {
}
