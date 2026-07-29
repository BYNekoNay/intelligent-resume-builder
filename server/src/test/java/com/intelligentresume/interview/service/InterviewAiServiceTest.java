package com.intelligentresume.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterviewAiServiceTest {

    @Mock
    private AiProviderRegistry providerRegistry;

    @Mock
    private AiProvider aiProvider;

    private InterviewAiService interviewAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        interviewAiService = new InterviewAiService(providerRegistry, objectMapper, validator);
    }

    // ---- promptVersion ----

    @Test
    @DisplayName("promptVersion returns interview-coach-v11")
    void promptVersion_returnsExpectedVersion() {
        assertEquals("interview-coach-v11", interviewAiService.promptVersion());
    }

    // ---- generateFirstQuestion ----

    @Test
    @DisplayName("generateFirstQuestion throws BusinessException when provider returns failure")
    void generateFirstQuestion_throwsOnProviderFailure() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.fail("Provider unavailable", true, "req-001"));

        InterviewAiService.AiInvocationException ex = assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.generateFirstQuestion("some context"));
        assertTrue(ex.getMessage().contains("Provider unavailable"),
                "Exception message should contain the provider error");
        assertEquals("req-001", ex.providerRequestId());
        assertTrue(ex.retryable());
    }

    @Test
    @DisplayName("generateFirstQuestion returns valid InitialQuestion when provider returns valid JSON")
    void generateFirstQuestion_returnsValidInitialQuestion() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        Map<String, Object> validResponse = buildValidInitialQuestionResponse();
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(validResponse, "req-002"));

        InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> invocation =
                interviewAiService.generateFirstQuestion("candidate context data");
        InterviewCoachResponse.InitialQuestion result = invocation.value();

        assertNotNull(result, "Result should not be null");
        assertEquals("req-002", invocation.providerRequestId());
        assertEquals("Tell me about your experience with distributed systems and microservices architecture.",
                result.getQuestion());
        assertEquals("Distributed systems", result.getFocus());
        assertEquals(2, result.getExpectedSignals().size());
        assertEquals(1, result.getCoverageTags().size());
    }

    @Test
    @DisplayName("generateFirstQuestion adds an explicit trusted output-language instruction")
    void generateFirstQuestion_addsExplicitLanguageInstruction() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(buildValidInitialQuestionResponse(), "req-language"));
        ArgumentCaptor<AiCallContext> contextCaptor = ArgumentCaptor.forClass(AiCallContext.class);

        interviewAiService.generateFirstQuestion("candidate context data");

        verify(aiProvider).call(contextCaptor.capture());
        assertTrue(String.valueOf(contextCaptor.getValue().input().get("_taskPrompt"))
                .contains("REQUIRED OUTPUT LANGUAGE: English"));
    }

    @Test
    @DisplayName("generateFirstQuestion uses the selected Chinese output language")
    void generateFirstQuestion_usesSelectedChineseLanguage() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(buildValidInitialQuestionResponse(), "req-language-zh"));
        ArgumentCaptor<AiCallContext> contextCaptor = ArgumentCaptor.forClass(AiCallContext.class);

        interviewAiService.generateFirstQuestion("candidate context data", InterviewOutputLanguage.ZH_CN);

        verify(aiProvider).call(contextCaptor.capture());
        assertTrue(String.valueOf(contextCaptor.getValue().input().get("_taskPrompt"))
                .contains("REQUIRED OUTPUT LANGUAGE: Simplified Chinese (zh-CN)"));
    }

    @Test
    @DisplayName("generateFirstQuestion throws BusinessException when question is too short (Jakarta validation fails)")
    void generateFirstQuestion_throwsOnInvalidQuestionLength() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        Map<String, Object> invalidResponse = new HashMap<>();
        Map<String, Object> initialQuestion = new HashMap<>();
        initialQuestion.put("question", "Short?");  // only 6 chars, min is 10
        initialQuestion.put("focus", "General");
        initialQuestion.put("expectedSignals", List.of("communication"));
        initialQuestion.put("coverageTags", List.of("intro"));
        invalidResponse.put("initialQuestion", initialQuestion);

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalidResponse, "req-003"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interviewAiService.generateFirstQuestion("some context"));
        assertTrue(ex.getMessage().contains("校验失败") || ex.getMessage().contains("契约"),
                "Exception should mention validation failure");
    }

    @Test
    @DisplayName("generateFirstQuestion throws BusinessException when AI returns null initialQuestion")
    void generateFirstQuestion_throwsWhenInitialQuestionIsNull() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        // Response without initialQuestion field
        Map<String, Object> emptyResponse = new HashMap<>();
        emptyResponse.put("answerEvaluation", null);

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(emptyResponse, "req-004"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interviewAiService.generateFirstQuestion("some context"));
        assertTrue(ex.getMessage().contains("首题"),
                "Exception message should mention missing first question");
    }

    // ---- evaluateAnswer ----

    @Test
    @DisplayName("evaluateAnswer throws BusinessException when provider returns failure")
    void evaluateAnswer_throwsOnProviderFailure() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.fail("Timeout occurred", false, "req-005"));

        InterviewAiService.AiInvocationException ex = assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
        assertTrue(ex.getMessage().contains("Timeout occurred"),
                "Exception message should contain the provider error");
        assertEquals("req-005", ex.providerRequestId());
        assertFalse(ex.retryable());
    }

    @Test
    @DisplayName("evaluateAnswer returns valid AnswerEvaluation when provider returns valid JSON")
    void evaluateAnswer_returnsValidAnswerEvaluation() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        Map<String, Object> validResponse = buildValidAnswerEvaluationResponse();
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(validResponse, "req-006"));

        InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> invocation =
                interviewAiService.evaluateAnswer("candidate answer context");
        InterviewCoachResponse.AnswerEvaluation result = invocation.value();

        assertNotNull(result, "Result should not be null");
        assertEquals("req-006", invocation.providerRequestId());
        assertNotNull(result.getDimensionScores(), "DimensionScores should not be null");
        assertEquals(20, result.getDimensionScores().getRelevance());
        assertEquals(18, result.getDimensionScores().getEvidenceSpecificity());
        assertEquals(15, result.getDimensionScores().getStructureClarity());
        assertEquals(16, result.getDimensionScores().getRoleCompetency());
        assertEquals(8, result.getDimensionScores().getAuthenticityReflection());
        assertEquals(1, result.getStrengths().size());
        assertEquals(1, result.getImprovements().size());
        assertNotNull(result.getNextQuestion(), "NextQuestion should not be null");
        assertFalse(result.isInformationComplete());
    }

    @Test
    @DisplayName("evaluateAnswer repairs an English evaluation in a Chinese session")
    void evaluateAnswer_repairsEnglishEvaluationInChineseSession() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(buildValidAnswerEvaluationResponse(), "req-language-drift"))
                .thenReturn(AiCallResult.ok(buildChineseAnswerEvaluationResponse(), "req-language-repaired"));
        Runnable quotaReservation = mock(Runnable.class);

        var result = interviewAiService.evaluateAnswer(
                "当前问题和回答均为中文", InterviewOutputLanguage.ZH_CN, quotaReservation);

        assertEquals("请补充故障恢复策略和验证结果。", result.value().getImprovements().get(0));
        assertEquals("req-language-repaired", result.providerRequestId());
        ArgumentCaptor<AiCallContext> contexts = ArgumentCaptor.forClass(AiCallContext.class);
        verify(aiProvider, times(2)).call(contexts.capture());
        assertEquals(2, contexts.getAllValues().size());
        assertTrue(String.valueOf(contexts.getAllValues().get(0).input().get("_taskPrompt"))
                .contains("REQUIRED OUTPUT LANGUAGE: Simplified Chinese (zh-CN)"));
        assertTrue(String.valueOf(contexts.getAllValues().get(1).input().get("_taskPrompt"))
                .contains("REQUIRED OUTPUT LANGUAGE: Simplified Chinese (zh-CN)"));
        verify(quotaReservation).run();
    }

    @Test
    @DisplayName("evaluateAnswer prompt defines strict numeric and conditional JSON fields")
    void evaluateAnswer_usesStrictJsonContractInstructions() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(buildValidAnswerEvaluationResponse(), "req-contract"));

        interviewAiService.evaluateAnswer("candidate answer context");

        ArgumentCaptor<AiCallContext> context = ArgumentCaptor.forClass(AiCallContext.class);
        verify(aiProvider).call(context.capture());
        String prompt = String.valueOf(context.getValue().input().get("_taskPrompt"));
        String systemPrompt = String.valueOf(context.getValue().input().get("_systemPrompt"));
        assertTrue(systemPrompt.contains("Scores are AWARDED POINTS, not penalties"));
        assertTrue(systemPrompt.contains("Negative scores are forbidden"));
        assertTrue(prompt.contains("dimensionScores are AWARDED POINTS"));
        assertTrue(prompt.contains("Never use negative numbers"));
        assertTrue(prompt.contains("relevance: 0 through 25"));
        assertTrue(prompt.contains("authenticityReflection: 0 through 10"));
        assertTrue(prompt.contains("\"relevance\":0"));
        assertTrue(prompt.contains("If evidence is absent, use 0 for that field"));
        assertFalse(prompt.contains("\"relevance\": 0-25"));
        assertTrue(prompt.contains("informationComplete is false, nextQuestion must be an object"));
        assertTrue(prompt.contains("informationComplete is true, nextQuestion must be null"));
        int untrustedStart = prompt.indexOf("[UNTRUSTED_USER_DATA]");
        int candidateContext = prompt.indexOf("candidate answer context");
        int untrustedEnd = prompt.indexOf("[/UNTRUSTED_USER_DATA]");
        int contract = prompt.indexOf("STRICT JSON CONTRACT:");
        assertTrue(untrustedStart < candidateContext);
        assertTrue(candidateContext < untrustedEnd);
        assertTrue(untrustedEnd < contract,
                "The JSON contract must remain outside the untrusted user-data boundary");
    }

    @Test
    @DisplayName("evaluateAnswer throws BusinessException when relevance score exceeds max of 25")
    void evaluateAnswer_throwsOnInvalidDimensionScore() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        Map<String, Object> invalidResponse = buildValidAnswerEvaluationResponse();
        // Override dimension scores with invalid relevance (> 25)
        Map<String, Object> invalidScores = new HashMap<>();
        invalidScores.put("relevance", 30);  // max is 25
        invalidScores.put("evidenceSpecificity", 18);
        invalidScores.put("structureClarity", 15);
        invalidScores.put("roleCompetency", 16);
        invalidScores.put("authenticityReflection", 8);

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) invalidResponse.get("answerEvaluation");
        evaluation.put("dimensionScores", invalidScores);

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalidResponse, "req-007"),
                        AiCallResult.ok(invalidResponse, "req-007-repair"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
        assertTrue(ex.getMessage().contains("校验失败") || ex.getMessage().contains("契约"),
                "Exception should mention validation failure for dimension score");
        verify(aiProvider, times(2)).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer repairs an out-of-range score exactly once")
    void evaluateAnswer_repairsOutOfRangeScoreOnce() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalidResponse = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) invalidResponse.get("answerEvaluation");
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) evaluation.get("dimensionScores");
        scores.replaceAll((key, value) -> -1);

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalidResponse, "req-invalid"),
                        AiCallResult.ok(buildValidAnswerEvaluationResponse(), "req-repaired"));

        InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> result =
                interviewAiService.evaluateAnswer("candidate answer context");

        assertEquals("req-repaired", result.providerRequestId());
        assertEquals(77, result.value().getDimensionScores().total());
        ArgumentCaptor<AiCallContext> calls = ArgumentCaptor.forClass(AiCallContext.class);
        verify(aiProvider, times(2)).call(calls.capture());
        String repairPrompt = String.valueOf(calls.getAllValues().get(1).input().get("_taskPrompt"));
        assertTrue(repairPrompt.contains("Negative numbers are forbidden"));
        assertTrue(repairPrompt.contains("Use 0, never -1"));
    }

    @Test
    @DisplayName("evaluateAnswer repairs a collection-size contract violation exactly once")
    void evaluateAnswer_repairsCollectionSizeViolationOnce() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalidResponse = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) invalidResponse.get("answerEvaluation");
        evaluation.put("strengths", List.of("one", "two", "three", "four"));

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalidResponse, "req-invalid-list"),
                        AiCallResult.ok(buildValidAnswerEvaluationResponse(), "req-repaired-list"));

        InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> result =
                interviewAiService.evaluateAnswer("candidate answer context");

        assertEquals("req-repaired-list", result.providerRequestId());
        assertEquals(1, result.value().getStrengths().size());
        verify(aiProvider, times(2)).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer accepts no strengths when the answer has no genuine positive evidence")
    void evaluateAnswer_acceptsEmptyStrengths() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("strengths", List.of());
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(response, "req-no-strengths"));

        InterviewCoachResponse.AnswerEvaluation result =
                interviewAiService.evaluateAnswer("irrelevant candidate answer").value();

        assertTrue(result.getStrengths().isEmpty());
        verify(aiProvider).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer throws BusinessException when AI returns null answerEvaluation")
    void evaluateAnswer_throwsWhenAnswerEvaluationIsNull() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);

        Map<String, Object> emptyResponse = new HashMap<>();
        emptyResponse.put("initialQuestion", null);

        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(emptyResponse, "req-008"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
        assertTrue(ex.getMessage().contains("评估"),
                "Exception message should mention missing answer evaluation");
    }

    @Test
    @DisplayName("evaluateAnswer accepts a completed evaluation without a next question")
    void evaluateAnswer_acceptsCompletedEvaluationWithoutNextQuestion() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("informationComplete", true);
        evaluation.put("completionReason", "The required competencies are covered.");
        evaluation.put("nextQuestion", null);
        when(aiProvider.call(any(AiCallContext.class))).thenReturn(AiCallResult.ok(response, "req-009"));

        var result = interviewAiService.evaluateAnswer("answer context").value();

        assertTrue(result.isInformationComplete());
        assertNull(result.getNextQuestion());
    }

    @Test
    @DisplayName("evaluateAnswer rejects an incomplete evaluation without a next question")
    void evaluateAnswer_rejectsIncompleteEvaluationWithoutNextQuestion() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("nextQuestion", null);
        when(aiProvider.call(any(AiCallContext.class))).thenReturn(AiCallResult.ok(response, "req-010"));

        assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
    }

    @Test
    @DisplayName("evaluateAnswer repairs a semantic contract violation once")
    void evaluateAnswer_repairsSemanticContractViolation() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalid = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> invalidEvaluation = (Map<String, Object>) invalid.get("answerEvaluation");
        invalidEvaluation.put("nextQuestion", null);
        Map<String, Object> repaired = buildValidAnswerEvaluationResponse();
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalid, "req-semantic-invalid"))
                .thenReturn(AiCallResult.ok(repaired, "req-semantic-repaired"));

        var result = interviewAiService.evaluateAnswer("answer context");

        assertNotNull(result.value().getNextQuestion());
        assertEquals("req-semantic-repaired", result.providerRequestId());
        verify(aiProvider, times(2)).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer reserves quota before a repair provider call")
    void evaluateAnswer_reservesQuotaBeforeRepair() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalid = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> invalidEvaluation = (Map<String, Object>) invalid.get("answerEvaluation");
        invalidEvaluation.put("nextQuestion", null);
        Map<String, Object> repaired = buildValidAnswerEvaluationResponse();
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalid, "req-invalid"))
                .thenReturn(AiCallResult.ok(repaired, "req-repaired"));
        Runnable quotaReservation = mock(Runnable.class);

        interviewAiService.evaluateAnswer("answer context", quotaReservation);

        verify(quotaReservation).run();
    }

    @Test
    @DisplayName("evaluateAnswer rejects omitted score fields after one repair")
    void evaluateAnswer_rejectsOmittedScoreFields() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalid = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) invalid.get("answerEvaluation");
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) evaluation.get("dimensionScores");
        scores.remove("relevance");
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalid, "req-missing-score"));

        assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
        verify(aiProvider, times(2)).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer rejects unknown JSON properties after one repair")
    void evaluateAnswer_rejectsUnknownProperties() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> invalid = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) invalid.get("answerEvaluation");
        evaluation.put("unexpectedField", "not allowed");
        when(aiProvider.call(any(AiCallContext.class)))
                .thenReturn(AiCallResult.ok(invalid, "req-unknown-field"));

        assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
        verify(aiProvider, times(2)).call(any(AiCallContext.class));
    }

    @Test
    @DisplayName("evaluateAnswer rejects an incomplete evaluation with a completion reason")
    void evaluateAnswer_rejectsIncompleteEvaluationWithCompletionReason() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("completionReason", "All competencies are covered.");
        when(aiProvider.call(any(AiCallContext.class))).thenReturn(AiCallResult.ok(response, "req-011"));

        assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
    }

    @Test
    @DisplayName("evaluateAnswer rejects a completed evaluation without a completion reason")
    void evaluateAnswer_rejectsCompletedEvaluationWithoutCompletionReason() {
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(aiProvider);
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("informationComplete", true);
        evaluation.put("nextQuestion", null);
        when(aiProvider.call(any(AiCallContext.class))).thenReturn(AiCallResult.ok(response, "req-012"));

        assertThrows(InterviewAiService.AiInvocationException.class,
                () -> interviewAiService.evaluateAnswer("answer context"));
    }

    // ---- Helper methods ----

    private Map<String, Object> buildValidInitialQuestionResponse() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> initialQuestion = new HashMap<>();
        initialQuestion.put("question",
                "Tell me about your experience with distributed systems and microservices architecture.");
        initialQuestion.put("focus", "Distributed systems");
        initialQuestion.put("expectedSignals",
                List.of("System design thinking", "Real project experience"));
        initialQuestion.put("coverageTags", List.of("technical"));
        response.put("initialQuestion", initialQuestion);
        return response;
    }

    private Map<String, Object> buildValidAnswerEvaluationResponse() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> evaluation = new HashMap<>();

        // Dimension scores
        Map<String, Object> scores = new HashMap<>();
        scores.put("relevance", 20);
        scores.put("evidenceSpecificity", 18);
        scores.put("structureClarity", 15);
        scores.put("roleCompetency", 16);
        scores.put("authenticityReflection", 8);
        evaluation.put("dimensionScores", scores);

        // Strengths and improvements
        evaluation.put("strengths",
                List.of("Clear explanation of microservices architecture with concrete examples."));
        evaluation.put("improvements",
                List.of("Could elaborate more on failure handling and recovery strategies."));
        evaluation.put("evidenceQuotes",
                List.of("We used event-driven communication between services."));

        // Suggested answer (min 50 chars)
        evaluation.put("suggestedAnswer",
                "In my previous role, I designed and implemented a microservices architecture using Spring Boot "
                        + "and Kafka for inter-service communication. We handled failures through circuit breakers "
                        + "and retry mechanisms, achieving 99.9% uptime across our distributed platform.");

        evaluation.put("resumeSuggestions",
                List.of("Add specific metrics about system throughput."));
        evaluation.put("expressionSuggestions",
                List.of("Use more structured STAR format for technical answers."));
        evaluation.put("coverageTags", List.of("architecture", "distributed"));

        evaluation.put("informationComplete", false);
        evaluation.put("completionReason", null);

        // Next question
        Map<String, Object> nextQuestion = new HashMap<>();
        nextQuestion.put("question",
                "Can you describe a challenging bug you encountered in production and how you resolved it?");
        nextQuestion.put("focus", "Problem solving");
        nextQuestion.put("expectedSignals",
                List.of("Debugging skills", "Systematic approach"));
        nextQuestion.put("coverageTags", List.of("problem-solving"));
        evaluation.put("nextQuestion", nextQuestion);

        response.put("answerEvaluation", evaluation);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildChineseAnswerEvaluationResponse() {
        Map<String, Object> response = buildValidAnswerEvaluationResponse();
        Map<String, Object> evaluation = (Map<String, Object>) response.get("answerEvaluation");
        evaluation.put("strengths", List.of("回答包含了明确的技术方案和项目背景。"));
        evaluation.put("improvements", List.of("请补充故障恢复策略和验证结果。"));
        evaluation.put("suggestedAnswer",
                "在上一家公司，我负责基于 Spring Boot 和 Kafka 的微服务架构设计，并通过熔断、重试和故障演练验证恢复能力，最终让核心服务可用性稳定达到百分之九十九点九。" );
        evaluation.put("resumeSuggestions", List.of("在简历中补充系统吞吐量和可用性指标。"));
        evaluation.put("expressionSuggestions", List.of("按照背景、任务、行动和结果组织回答。"));
        Map<String, Object> nextQuestion = (Map<String, Object>) evaluation.get("nextQuestion");
        nextQuestion.put("question", "请描述一次生产故障以及你如何定位并解决问题？");
        nextQuestion.put("focus", "故障排查能力");
        nextQuestion.put("expectedSignals", List.of("系统化排查过程", "可验证的恢复结果"));
        return response;
    }
}
