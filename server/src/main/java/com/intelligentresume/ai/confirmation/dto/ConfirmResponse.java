package com.intelligentresume.ai.confirmation.dto;

import java.util.List;

public record ConfirmResponse(
        Long resumeVersionId,
        Integer versionNo,
        Long resultResumeVersionId,
        List<String> rejectedPaths,
        List<Long> newMaterialIds
) {}