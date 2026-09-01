package com.intelligentresume.communication.service;

import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CommunicationAiService {
    private final AiProviderRegistry providerRegistry;
    private final CommunicationAiPromptBuilder promptBuilder;
    private final CommunicationAiResultValidator validator;
    private final CommunicationDraftRepository draftRepository;

    public CommunicationAiService(AiProviderRegistry providerRegistry, CommunicationAiPromptBuilder promptBuilder,
                                  CommunicationAiResultValidator validator, CommunicationDraftRepository draftRepository) {
        this.providerRegistry = providerRegistry;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.draftRepository = draftRepository;
    }

    public ExecutionResult executeTask(AiTask task) {
        Map<String, Object> input = taskInput(task);
        CommunicationType type = parseType(input.get("type"));
        String outputLanguage = "EN".equals(input.get("outputLanguage")) ? "EN" : "ZH_CN";
        AiProvider provider = providerRegistry.route(AiTaskType.COMMUNICATION_GENERATE);
        AiCallResult response = call(provider, promptBuilder.build(input));
        CommunicationAiResultValidator.ValidatedResult validated;
        try {
            validated = validator.validate(response.data(), type, outputLanguage);
        } catch (CommunicationAiException firstError) {
            response = call(provider, promptBuilder.buildRepair(input, response.data(), firstError.getMessage()));
            validated = validator.validate(response.data(), type, outputLanguage);
        }

        String draft = composeDraft(validated, type, outputLanguage);
        long resumeVersionId = longValue(input.get("resumeVersionId"), "resumeVersionId");
        long jobDescriptionId = longValue(input.get("jobDescriptionId"), "jobDescriptionId");
        CommunicationDraft draftToSave = new CommunicationDraft();
        draftToSave.setUserId(task.getUserId());
        draftToSave.setResumeVersionId(resumeVersionId);
        draftToSave.setJobDescriptionId(jobDescriptionId);
        draftToSave.setType(type);
        draftToSave.setDraftText(draft);
        CommunicationDraft entity = draftRepository
                .findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
                        task.getUserId(), resumeVersionId, jobDescriptionId, type, draft)
                .orElseGet(() -> draftRepository.save(draftToSave));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.name());
        result.put("subject", validated.subject());
        result.put("body", validated.body());
        result.put("draft", draft);
        result.put("generationSource", "AI");
        result.put("communicationDraftId", entity.getId());
        result.put("resumeVersionId", resumeVersionId);
        result.put("jobDescriptionId", jobDescriptionId);
        result.put("promptVersion", promptBuilder.promptVersion());
        result.put("schemaVersion", promptBuilder.schemaVersion());
        result.put("providerRequestId", response.providerRequestId());
        return new ExecutionResult(result);
    }

    private AiCallResult call(AiProvider provider, Map<String, Object> prompt) {
        AiCallResult result = provider.call(new AiCallContext(AiTaskType.COMMUNICATION_GENERATE, prompt));
        if (result.success()) return result;
        throw new CommunicationAiException(
                result.errorMessage() == null ? "Communication AI provider failed" : result.errorMessage(),
                result.retryable());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskInput(AiTask task) {
        if (task.getInputSnapshotJson() != null && task.getInputSnapshotJson().get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        throw new CommunicationAiException("Communication AI task input is missing", false);
    }

    private CommunicationType parseType(Object value) {
        try {
            return CommunicationType.valueOf(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new CommunicationAiException("Communication type is invalid", false);
        }
    }

    private long longValue(Object value, String name) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new CommunicationAiException(name + " is invalid", false);
        }
    }

    private String composeDraft(CommunicationAiResultValidator.ValidatedResult result,
                                CommunicationType type, String language) {
        if (type != CommunicationType.EMAIL) return result.body();
        return ("EN".equals(language) ? "Subject: " : "主题：") + result.subject() + "\n\n" + result.body();
    }

    public record ExecutionResult(Map<String, Object> taskResult) {
    }
}
