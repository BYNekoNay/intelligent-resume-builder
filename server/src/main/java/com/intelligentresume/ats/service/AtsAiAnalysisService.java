package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AtsAiAnalysisService {
    private final AiProviderRegistry providerRegistry;
    private final AtsAiPromptBuilder promptBuilder;
    private final AtsAiResultValidator validator;
    private final ObjectMapper objectMapper;

    public AtsAiAnalysisService(AiProviderRegistry providerRegistry, AtsAiPromptBuilder promptBuilder,
                                AtsAiResultValidator validator, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(AiTask task) {
        Map<String, Object> input = taskInput(task);
        AiProvider provider = providerRegistry.route(AiTaskType.ATS_ANALYSIS);
        AiCallResult response = call(provider, promptBuilder.build(input));
        AtsAiInsights insights;
        try {
            insights = validator.validate(response.data(), input.get("resumeJson"));
        } catch (AtsAiAnalysisException firstError) {
            if (firstError.fallbackCode() != AtsFallbackCode.INVALID_RESPONSE) throw firstError;
            response = call(provider, promptBuilder.buildRepair(input, response.data(), firstError.getMessage()));
            insights = validator.validate(response.data(), input.get("resumeJson"));
        }
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("atsCheckResultId", input.get("atsCheckResultId"));
        taskResult.put("aiInsights", objectMapper.convertValue(insights, Map.class));
        taskResult.put("providerRequestId", response.providerRequestId());
        taskResult.put("promptVersion", input.get("promptVersion"));
        taskResult.put("schemaVersion", input.get("schemaVersion"));
        return new AnalysisResult(insights, taskResult);
    }

    private AiCallResult call(AiProvider provider, Map<String, Object> prompt) {
        AiCallResult response = provider.call(new AiCallContext(AiTaskType.ATS_ANALYSIS, prompt));
        if (response.success()) return response;
        String message = response.errorMessage() == null ? "AI ATS analysis failed" : response.errorMessage();
        String normalized = message.toLowerCase();
        AtsFallbackCode code = normalized.contains("timeout") || normalized.contains("timed out") || message.contains("超时")
                ? AtsFallbackCode.PROVIDER_TIMEOUT : AtsFallbackCode.PROVIDER_ERROR;
        throw new AtsAiAnalysisException(code, message, response.retryable());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskInput(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot != null && snapshot.get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        throw new AtsAiAnalysisException(AtsFallbackCode.INVALID_RESPONSE, "ATS task input is missing", false);
    }

    public record AnalysisResult(AtsAiInsights insights, Map<String, Object> taskResult) {
    }
}
