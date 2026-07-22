package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.generation.prompt.PromptBuilder;
import com.intelligentresume.ai.generation.validator.JobGenerationSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deliberately opt-in: it sends a small, non-sensitive prompt to Bailian. */
@EnabledIfEnvironmentVariable(named = "BAILIAN_LIVE_TEST", matches = "true")
class BailianAiProviderLiveIT {

    @Test
    void returnsStructuredAchievementGuidanceFromTheConfiguredModelChain() {
        Map<String, Object> result = provider().invoke("ACHIEVEMENT_GUIDANCE", Map.of(
                "content", "Implemented a small internal tool and reduced a manual review step."));

        assertThat(result.get("questions")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("questions")).isNotEmpty();
        printResult("achievement_guidance", result);
    }

    @Test
    void returnsUsableInlineOptimizationCandidates() {
        Map<String, Object> result = provider().invoke("INLINE_OPTIMIZE", Map.of(
                "section", "work",
                "content", "Built an order API in Spring Boot and reduced checkout errors from 3% to 1.2%.",
                "resumeContext", Map.of("basics", Map.of("name", "Test Candidate")),
                "jobDescription", "Backend engineer with Kotlin and Spring Boot experience."));

        assertThat(result.get("candidates")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("candidates")).isNotEmpty();
        assertThat(String.valueOf(result.get("candidates"))).doesNotContainIgnoringCase("Kotlin");
        printResult("inline_optimize", result);
    }

    @Test
    void returnsACommunicationDraftGroundedInSuppliedFacts() {
        Map<String, Object> result = provider().invoke("COMMUNICATION_GENERATE", Map.of(
                "type", "COVER_LETTER",
                "resume", Map.of("basics", Map.of("name", "Test Candidate"),
                        "work", List.of(Map.of("company", "Example Systems", "position", "Backend Engineer",
                                "highlights", List.of("Reduced checkout errors from 3% to 1.2%."))),
                        "skills", List.of("Java", "Spring Boot", "MySQL")),
                "jobTitle", "Backend Engineer",
                "jobText", "Build reliable Java services with Spring Boot and MySQL."));

        assertThat(String.valueOf(result.get("draft"))).containsIgnoringCase("Spring");
        printResult("communication_generate", result);
    }

    @Test
    void returnsAStructuredMaterialResumeDraft() {
        Map<String, Object> result = provider().invoke("MATERIAL_RESUME_GENERATION", Map.of(
                "rawMaterialText", "Test Candidate. Backend engineer at Example Systems. Built an order API with Java, Spring Boot, and MySQL. Reduced checkout errors from 3% to 1.2%.",
                "jobDescriptionId", ""));

        assertThat(result.get("generatedResumeJson")).isInstanceOf(Map.class);
        assertThat(result.get("suggestions")).isInstanceOf(List.class);
        printResult("material_resume_generation", result);
    }

    @Test
    void returnsAJobDraftThatPassesTheApplicationSchema() {
        String prompt = new PromptBuilder().buildJobGenerationPrompt(Map.of(
                "jdText", "Backend engineer: Java, Spring Boot, MySQL, reliable API delivery.",
                "parsedKeywords", Map.of("keywords", List.of("Java", "Spring Boot", "MySQL")),
                "materials", List.of(Map.of("id", 101, "title", "Order API", "materialType", "PROJECT_EXPERIENCE",
                        "sourceText", "Built an order API with Java, Spring Boot, and MySQL. Reduced checkout errors from 3% to 1.2%."))));
        Map<String, Object> result = provider().invoke("JOB_GENERATION", Map.of("prompt", prompt));

        new JobGenerationSchemaValidator().validate(result);
        printResult("job_generation", result);
    }

    private BailianAiProvider provider() {
        String apiKey = System.getenv("BAILIAN_API_KEY");
        assertThat(apiKey).isNotBlank();
        return new BailianAiProvider(
                RestClient.builder().baseUrl(System.getenv().getOrDefault("BAILIAN_BASE_URL",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1")).build(),
                new ObjectMapper(), apiKey, System.getenv().getOrDefault("BAILIAN_MODELS", "deepseek-v3.2"));
    }

    private void printResult(String task, Map<String, Object> result) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("keys", result.keySet());
        result.forEach((key, value) -> {
            if (value instanceof List<?> list) summary.put(key + "Count", list.size());
            else if (value instanceof Map<?, ?> map) summary.put(key + "Keys", map.keySet());
            else summary.put(key + "Present", value != null);
        });
        System.out.println("LIVE_AI_SUMMARY " + task + " " + summary);
    }
}
