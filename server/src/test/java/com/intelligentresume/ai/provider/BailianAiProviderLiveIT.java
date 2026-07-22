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
                "content", "实现内部审核工具，减少了人工审核步骤。"));

        assertThat(result.get("questions")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("questions")).isNotEmpty();
        assertThat(String.valueOf(result.get("questions"))).containsPattern("\\p{IsHan}");
        printResult("achievement_guidance", result);
    }

    @Test
    void returnsUsableInlineOptimizationCandidates() {
        Map<String, Object> result = provider().invoke("INLINE_OPTIMIZE", Map.of(
                "section", "work",
                "content", "使用 Spring Boot 构建订单接口，将结算错误率从 3% 降至 1.2%。",
                "resumeContext", Map.of("basics", Map.of("name", "测试候选人")),
                "jobDescription", "需要 Kotlin 和 Spring Boot 经验的后端工程师。"));

        assertThat(result.get("candidates")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("candidates")).isNotEmpty();
        assertThat(String.valueOf(result.get("candidates"))).doesNotContainIgnoringCase("Kotlin");
        assertThat(String.valueOf(result.get("candidates"))).containsPattern("\\p{IsHan}");
        printResult("inline_optimize", result);
    }

    @Test
    void returnsACommunicationDraftGroundedInSuppliedFacts() {
        Map<String, Object> result = provider().invoke("COMMUNICATION_GENERATE", Map.of(
                "type", "COVER_LETTER",
                "resume", Map.of("basics", Map.of("name", "测试候选人"),
                        "work", List.of(Map.of("company", "示例科技", "position", "后端工程师",
                                "highlights", List.of("将结算错误率从 3% 降至 1.2%。"))),
                        "skills", List.of("Java", "Spring Boot", "MySQL")),
                "jobTitle", "后端工程师",
                "jobText", "使用 Java、Spring Boot 与 MySQL 构建可靠服务。"));

        assertThat(String.valueOf(result.get("draft"))).containsIgnoringCase("Spring");
        assertThat(String.valueOf(result.get("draft"))).containsPattern("\\p{IsHan}");
        assertThat(String.valueOf(result.get("draft"))).doesNotContainIgnoringCase("Kotlin");
        printResult("communication_generate", result);
    }

    @Test
    void returnsAStructuredMaterialResumeDraft() {
        Map<String, Object> result = provider().invoke("MATERIAL_RESUME_GENERATION", Map.of(
                "rawMaterialText", "测试候选人，示例科技后端工程师。使用 Java、Spring Boot 和 MySQL 构建订单接口，将结算错误率从 3% 降至 1.2%。",
                "jobDescriptionId", ""));

        assertThat(result.get("generatedResumeJson")).isInstanceOf(Map.class);
        assertThat(result.get("suggestions")).isInstanceOf(List.class);
        Map<?, ?> generatedResume = (Map<?, ?>) result.get("generatedResumeJson");
        assertThat(generatedResume.get("work")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isNotEmpty();
        assertThat(generatedResume.get("skills")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isNotEmpty();
        assertThat(String.valueOf(result)).containsPattern("\\p{IsHan}");
        assertThat(String.valueOf(result)).doesNotContainIgnoringCase("Kotlin");
        printResult("material_resume_generation", result);
    }

    @Test
    void returnsAJobDraftThatPassesTheApplicationSchema() {
        String prompt = new PromptBuilder().buildJobGenerationPrompt(Map.of(
                "jdText", "后端工程师：Java、Spring Boot、MySQL 与可靠接口交付。",
                "parsedKeywords", Map.of("keywords", List.of("Java", "Spring Boot", "MySQL")),
                "materials", List.of(Map.of("id", 101, "title", "订单接口", "materialType", "PROJECT_EXPERIENCE",
                        "sourceText", "使用 Java、Spring Boot 和 MySQL 构建订单接口，将结算错误率从 3% 降至 1.2%。"))));
        Map<String, Object> result = provider().invoke("JOB_GENERATION", Map.of("prompt", prompt));

        new JobGenerationSchemaValidator().validate(result);
        assertThat(String.valueOf(result)).containsPattern("\\p{IsHan}");
        assertThat(String.valueOf(result)).doesNotContainIgnoringCase("Kotlin");
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
