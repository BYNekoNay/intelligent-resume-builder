package com.intelligentresume.resume.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ResumeMaterialReferenceResponse(Long id, Long resumeVersionId, Long materialId,
                                              String selectionStatus, String outputPath,
                                              Map<String, Object> sourceSnapshotJson,
                                              String selectionReason, LocalDateTime createdAt) {}
