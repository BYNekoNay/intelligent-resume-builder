package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.service.InterviewAiService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import com.intelligentresume.communication.service.CommunicationAiPromptBuilder;
import com.intelligentresume.communication.service.CommunicationAiResultValidator;
import com.intelligentresume.communication.service.CommunicationAiService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@EnabledIfEnvironmentVariable(named = "BAILIAN_LIVE_TEST", matches = "true")
class BailianAiProviderLiveIT {

    @Test
    void generatesAValidatedEnglishCommunicationDraft() {
        String apiKey = requiredEnvironment("BAILIAN_API_KEY");
        String baseUrl = environmentOrDefault(
                "BAILIAN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = environmentOrDefault("BAILIAN_MODEL", "qwen-plus");
        ObjectMapper objectMapper = new ObjectMapper();
        BailianAiProvider provider = new BailianAiProvider(
                baseUrl, apiKey, model, 10, 60, objectMapper,
                mock(AppObservability.class), new FailureCategoryClassifier());
        CommunicationDraftRepository repository = mock(CommunicationDraftRepository.class);
        when(repository.findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CommunicationAiService service = new CommunicationAiService(
                new AiProviderRegistry(List.of(provider)),
                new CommunicationAiPromptBuilder(objectMapper, "communication-live-v1", "communication-schema-v1"),
                new CommunicationAiResultValidator(), repository);
        AiTask task = new AiTask();
        task.setId(9001L);
        task.setUserId(1L);
        task.setTaskType(AiTaskType.COMMUNICATION_GENERATE);
        task.setInputSnapshotJson(Map.of("input", Map.of(
                "resumeVersionId", 11L,
                "jobDescriptionId", 22L,
                "type", "EMAIL",
                "outputLanguage", "EN",
                "resumeJson", Map.of(
                        "basics", Map.of("name", "Alex Chen"),
                        "skills", List.of(Map.of("name", "Java")),
                        "work", List.of(Map.of("position", "Backend Engineer",
                                "summary", "Built reliable Spring Boot services"))),
                "job", Map.of("title", "Backend Engineer", "companyName", "Example Systems",
                        "jdText", "Build Java and Spring Boot services with strong reliability practices"),
                "promptVersion", "communication-live-v1",
                "schemaVersion", "communication-schema-v1")));

        var result = service.executeTask(task).taskResult();

        assertEquals("AI", result.get("generationSource"));
        assertEquals("EMAIL", result.get("type"));
        assertTrue(String.valueOf(result.get("draft")).startsWith("Subject: "));
        assertFalse(String.valueOf(result.get("body")).isBlank());
        assertNotNull(result.get("providerRequestId"));
    }

    @Test
    void completesTheInterviewCoachStructuredResponseContract() {
        String apiKey = requiredEnvironment("BAILIAN_API_KEY");
        String baseUrl = environmentOrDefault(
                "BAILIAN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = environmentOrDefault("BAILIAN_MODEL", "deepseek-v3.2");

        ObjectMapper objectMapper = new ObjectMapper();
        BailianAiProvider provider = new BailianAiProvider(
                baseUrl,
                apiKey,
                model,
                10,
                60,
                objectMapper,
                mock(AppObservability.class),
                new FailureCategoryClassifier()
        );
        AtomicReference<Map<String, Object>> lastResponse = new AtomicReference<>();
        AiProvider capturingProvider = new AiProvider() {
            @Override public String code() { return provider.code(); }
            @Override public String modelCode() { return provider.modelCode(); }
            @Override public boolean supports(com.intelligentresume.ai.task.domain.AiTaskType type) {
                return provider.supports(type);
            }
            @Override public AiCallResult call(AiCallContext context) {
                AiCallResult result = provider.call(context);
                lastResponse.set(result.data());
                return result;
            }
        };

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            InterviewAiService service = new InterviewAiService(
                    new AiProviderRegistry(List.of(capturingProvider)),
                    objectMapper,
                    validatorFactory.getValidator()
            );

            var first = service.generateFirstQuestion("""
                    Candidate resume: Java backend engineer. Built a Spring Boot order service and
                    reduced P99 latency by 30 percent after profiling database connection usage.
                    Target role: Backend engineer requiring Java, Spring Boot, MySQL, and reliability.
                    Interview mode: TECHNICAL. Target questions: 6. Minimum questions: 3.
                    Completed questions: 0.
                    """);

            assertNotNull(first.providerRequestId());
            assertFalse(first.providerRequestId().isBlank());
            assertNotNull(first.value());
            assertFalse(first.value().getQuestion().isBlank());

            InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> evaluation;
            try {
                evaluation = service.evaluateAnswer("""
                        Candidate resume: Java backend engineer with Spring Boot and MySQL experience.
                        Target role: Backend engineer requiring reliability and evidence-based problem solving.
                        Interview progress: current question 1, completed questions 0, minimum questions 3.
                        Current question: Describe how you diagnosed a production performance problem.
                        Current answer: I inspected latency metrics, traced the bottleneck to an exhausted
                        database connection pool, adjusted pool sizing and query behavior, and verified a
                        30 percent reduction in P99 latency with the same production-like load test.
                        """);
            } catch (InterviewAiService.AiInvocationException failure) {
                fail("Interview response contract rejected provider shape: "
                        + describeShape(lastResponse.get()), failure);
                return;
            }

            InterviewCoachResponse.AnswerEvaluation result = evaluation.value();
            assertNotNull(evaluation.providerRequestId());
            assertFalse(evaluation.providerRequestId().isBlank());
            assertNotNull(result.getDimensionScores());
            assertFalse(result.getStrengths().isEmpty());
            assertFalse(result.getImprovements().isEmpty());
            assertTrue(result.getDimensionScores().total() >= 0);
            assertTrue(result.getDimensionScores().total() <= 100);
            assertFalse(result.isInformationComplete());
            assertNotNull(result.getNextQuestion());
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be configured for the live gate");
        assertFalse(value.isBlank(), name + " must be configured for the live gate");
        return value;
    }

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Object describeShape(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> shape = new java.util.LinkedHashMap<>();
            map.forEach((key, nested) -> shape.put(String.valueOf(key), describeShape(nested)));
            return shape;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "list(empty)" : "list(" + list.size() + ", " + describeShape(list.get(0)) + ")";
        }
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
