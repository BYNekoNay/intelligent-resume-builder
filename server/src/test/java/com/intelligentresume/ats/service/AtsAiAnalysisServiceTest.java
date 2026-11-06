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
    void classifiesTimeoutsFromStructuredAttemptCategory_evenWithoutKeywordInMessage() {
        // #67：真实 provider 超时路径（BailianAiProvider 捕获 ResourceAccessException）返回的
        // errorMessage 是「百炼 API 网络异常」——文案不含 timeout/超时；结构化信号在
        // modelAttempts 的 failureCategory（AiFailureCategory.TIMEOUT.name()）里。
        // 判定必须优先用结构化类别，文案匹配只作兜底。
        when(provider.call(any())).thenReturn(
                AiCallResult.fail("百炼 API 网络异常", true, "req-net")
                        .withAttempts(List.of(new AiCallResult.ModelAttempt("qwen-max", "TIMEOUT", "百炼 API 网络异常"))));

        AtsAiAnalysisException error = assertThrows(AtsAiAnalysisException.class,
                () -> service.analyze(task()));

        assertEquals(AtsFallbackCode.PROVIDER_TIMEOUT, error.fallbackCode());
        assertTrue(error.retryable());
    }

    @Test
    void stillFallsBackToMessageMatchingWhenAttemptsCarryNoCategory() {
        // #67：无 attempts（如单次失败路径）时文案匹配兜底仍生效，超时路径仍落 PROVIDER_TIMEOUT。
        when(provider.call(any())).thenReturn(AiCallResult.fail("百炼 API 网络异常", true, "req-no-attempts"));

        AtsAiAnalysisException error = assertThrows(AtsAiAnalysisException.class,
                () -> service.analyze(task()));

        // 无结构化信号、文案也不含 timeout 关键词 → 只能落 PROVIDER_ERROR（文档化现状）
        assertEquals(AtsFallbackCode.PROVIDER_ERROR, error.fallbackCode());
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

    @Test
    void recordsVersionsFromPromptBuilder_notStaleSnapshotCopy() {
        when(provider.call(any())).thenReturn(AiCallResult.ok(validOutput("ok"), "req-version"));
        // 任务快照里带一个过期的版本副本：结果必须记录构建器实际使用的版本（#85）
        AiTask task = new AiTask();
        task.setInputSnapshotJson(Map.of("input", Map.of(
                "atsCheckResultId", 9L,
                "resumeJson", Map.of("basics", Map.of("name", "Alice")),
                "jdText", "Java backend engineer",
                "promptVersion", "stale-v0",
                "schemaVersion", "stale-v0")));

        AtsAiAnalysisService.AnalysisResult result = service.analyze(task);

        assertEquals("v1", result.taskResult().get("promptVersion"));
        assertEquals("v1", result.taskResult().get("schemaVersion"));
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
