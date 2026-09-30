package com.intelligentresume.communication.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunicationAiServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesChineseEmailAndPersistsDraftOnlyInsideTheLeaseTransaction() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.supports(AiTaskType.COMMUNICATION_GENERATE)).thenReturn(true);
        when(provider.call(any())).thenReturn(AiCallResult.ok(response(
                "高级 Java 工程师岗位申请",
                "您好，我具备 Java 与高可用服务建设经验，希望应聘高级 Java 工程师岗位。"), "req-1"));
        CommunicationDraftRepository repository = mock(CommunicationDraftRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            CommunicationDraft saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        CommunicationAiService service = service(provider, repository);

        var result = service.executeTask(task("EMAIL", "ZH_CN"));

        // #29：executeTask 内不落库（草稿必须与任务结果同事务落库）
        verify(repository, never()).save(any(CommunicationDraft.class));
        assertEquals("AI", result.taskResult().get("generationSource"));
        assertTrue(String.valueOf(result.taskResult().get("draft")).startsWith("主题：高级 Java 工程师岗位申请"));
        assertEquals(3L, result.pendingDraft().userId());
        assertEquals(11L, result.pendingDraft().resumeVersionId());
        assertEquals(22L, result.pendingDraft().jobDescriptionId());

        service.persistDraft(result.pendingDraft(), result.taskResult());

        verify(repository).save(any(CommunicationDraft.class));
        assertEquals(99L, result.taskResult().get("communicationDraftId"));

        ArgumentCaptor<AiCallContext> context = ArgumentCaptor.forClass(AiCallContext.class);
        verify(provider).call(context.capture());
        assertTrue(String.valueOf(context.getValue().input().get("_systemPrompt"))
                .contains("REQUIRED OUTPUT LANGUAGE: Simplified Chinese (zh-CN)"));
        assertTrue(String.valueOf(context.getValue().input().get("_dataPrompt"))
                .contains("[UNTRUSTED_USER_DATA]"));
    }

    @Test
    void persistDraftReusesAnIdenticalExistingDraftRow() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.supports(AiTaskType.COMMUNICATION_GENERATE)).thenReturn(true);
        when(provider.call(any())).thenReturn(AiCallResult.ok(response(null,
                "您好，我希望应聘高级 Java 工程师岗位，并进一步介绍我在高可用服务与性能治理方面的项目经验。"), "req-1"));
        CommunicationDraftRepository repository = mock(CommunicationDraftRepository.class);
        CommunicationDraft existing = new CommunicationDraft();
        existing.setId(77L);
        when(repository.findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
                any(), anyLong(), anyLong(), any(), any())).thenReturn(java.util.Optional.of(existing));
        CommunicationAiService service = service(provider, repository);

        var result = service.executeTask(task("OPENING_MESSAGE", "ZH_CN"));
        service.persistDraft(result.pendingDraft(), result.taskResult());

        verify(repository, never()).save(any(CommunicationDraft.class));
        assertEquals(77L, result.taskResult().get("communicationDraftId"));
    }

    @Test
    void repairsLanguageDriftWithTheSameChineseInstruction() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.supports(AiTaskType.COMMUNICATION_GENERATE)).thenReturn(true);
        when(provider.call(any()))
                .thenReturn(AiCallResult.ok(response(null,
                        "Hello, I would like to apply for the senior Java engineer role and discuss my experience."), "req-1"))
                .thenReturn(AiCallResult.ok(response(null,
                        "您好，我希望应聘高级 Java 工程师岗位，并进一步介绍相关项目经验。"), "req-2"));
        CommunicationDraftRepository repository = mock(CommunicationDraftRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CommunicationAiService service = service(provider, repository);

        var result = service.executeTask(task("OPENING_MESSAGE", "ZH_CN"));

        assertEquals("您好，我希望应聘高级 Java 工程师岗位，并进一步介绍相关项目经验。",
                result.taskResult().get("draft"));
        ArgumentCaptor<AiCallContext> contexts = ArgumentCaptor.forClass(AiCallContext.class);
        verify(provider, times(2)).call(contexts.capture());
        for (AiCallContext context : contexts.getAllValues()) {
            assertTrue(String.valueOf(context.input().get("_systemPrompt"))
                    .contains("REQUIRED OUTPUT LANGUAGE: Simplified Chinese (zh-CN)"));
        }
    }

    private CommunicationAiService service(AiProvider provider, CommunicationDraftRepository repository) {
        CommunicationAiPromptBuilder promptBuilder = new CommunicationAiPromptBuilder(objectMapper, "communication-v1", "communication-schema-v1");
        return new CommunicationAiService(new AiProviderRegistry(List.of(provider)), promptBuilder,
                new CommunicationAiResultValidator(), repository);
    }

    private AiTask task(String type, String language) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("resumeVersionId", 11L);
        input.put("jobDescriptionId", 22L);
        input.put("type", type);
        input.put("outputLanguage", language);
        input.put("resumeJson", Map.of(
                "basics", Map.of("name", "张明远"),
                "skills", List.of(Map.of("name", "Java"))));
        input.put("job", Map.of("title", "高级 Java 工程师", "companyName", "星云科技", "jdText", "负责高可用 Java 服务"));
        input.put("promptVersion", "communication-v1");
        input.put("schemaVersion", "communication-schema-v1");
        AiTask task = new AiTask();
        task.setId(7L);
        task.setUserId(3L);
        task.setTaskType(AiTaskType.COMMUNICATION_GENERATE);
        task.setInputSnapshotJson(Map.of("input", input));
        return task;
    }

    private Map<String, Object> response(String subject, String body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subject", subject);
        response.put("body", body);
        return response;
    }
}
