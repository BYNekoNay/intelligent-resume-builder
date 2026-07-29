package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtsAiAnalysisServiceTest {
    private AiProvider provider;
    private AtsAiAnalysisService service;

    @BeforeEach
    void setUp() {
        provider = mock(AiProvider.class);
        when(provider.supports(any())).thenReturn(true);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AtsAiAnalysisService(
                new AiProviderRegistry(List.of(provider)),
                new AtsAiPromptBuilder(objectMapper, "v1", "v1"),
                new AtsAiResultValidator(objectMapper), objectMapper);
    }

    @Test
    void classifiesProviderTimeouts() {
        when(provider.call(any())).thenReturn(AiCallResult.fail("Read timed out", true, "req-timeout"));

        AtsAiAnalysisException error = assertThrows(AtsAiAnalysisException.class,
                () -> service.analyze(task()));

        assertEquals(AtsFallbackCode.PROVIDER_TIMEOUT, error.fallbackCode());
        assertTrue(error.retryable());
    }

    @Test
    void classifiesProviderServerErrors() {
        when(provider.call(any())).thenReturn(AiCallResult.fail("Provider returned HTTP 503", true, "req-503"));

        AtsAiAnalysisException error = assertThrows(AtsAiAnalysisException.class,
                () -> service.analyze(task()));

        assertEquals(AtsFallbackCode.PROVIDER_ERROR, error.fallbackCode());
    }

    @Test
    void repairsOneInvalidResponseBeforeCompleting() {
        when(provider.call(any()))
                .thenReturn(AiCallResult.ok(validOutput(""), "req-invalid"))
                .thenReturn(AiCallResult.ok(validOutput("Useful ATS summary"), "req-repaired"));

        AtsAiAnalysisService.AnalysisResult result = service.analyze(task());

        assertEquals("Useful ATS summary", result.insights().summary());
        assertEquals("req-repaired", result.taskResult().get("providerRequestId"));
        verify(provider, times(2)).call(any());
    }

    private Map<String, Object> validOutput(String summary) {
        return Map.of(
                "summary", summary,
                "semanticCoverage", List.of(Map.of(
                        "requirement", "Java", "status", "MATCHED", "evidence", "Java", "reason", "Direct match")),
                "evidenceFindings", List.of(Map.of(
                        "section", "skills", "quote", "Java", "assessment", "Relevant", "suggestion", "Add scale")),
                "readabilityRisks", List.of("Keep headings conventional"),
                "prioritizedActions", List.of(Map.of(
                        "priority", "P1", "section", "work", "action", "Add metrics", "basis", "Evidence quality")),
                "confidence", "MEDIUM");
    }

    private AiTask task() {
        AiTask task = new AiTask();
        task.setInputSnapshotJson(Map.of("input", Map.of(
                "atsCheckResultId", 9L,
                "resumeJson", Map.of("basics", Map.of("name", "Alice")),
                "jdText", "Java backend engineer")));
        return task;
    }
}
