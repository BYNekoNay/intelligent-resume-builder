package com.intelligentresume.export.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelligentresume.export.domain.ExportTask;

import java.time.LocalDateTime;

public record ExportTaskResponse(
        Long id,
        Long resumeVersionId,
        String templateCode,
        ExportTask.ExportStatus status,
        Long fileSizeBytes,
        String sha256,
        String errorMessage,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    @JsonProperty("taskId")
    public Long taskId() {
        return id;
    }
}
