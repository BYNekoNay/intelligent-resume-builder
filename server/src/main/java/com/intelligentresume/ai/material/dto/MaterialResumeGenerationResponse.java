package com.intelligentresume.ai.material.dto;
import java.util.List;
import java.util.Map;
public record MaterialResumeGenerationResponse(Long taskId, String rawMaterialText, Map<String, Object> generatedResumeJson, List<String> suggestions, boolean requiresManualConfirmation) { }
