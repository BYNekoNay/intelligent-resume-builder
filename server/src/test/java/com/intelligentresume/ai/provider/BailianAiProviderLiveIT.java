package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        String apiKey = System.getenv("BAILIAN_API_KEY");
        assertThat(apiKey).isNotBlank();
        String models = System.getenv().getOrDefault("BAILIAN_MODELS", "deepseek-v3.2");
        BailianAiProvider provider = new BailianAiProvider(
                RestClient.builder()
                        .baseUrl(System.getenv().getOrDefault("BAILIAN_BASE_URL",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                        .build(),
                new ObjectMapper(), apiKey, models);

        Map<String, Object> result = provider.invoke("ACHIEVEMENT_GUIDANCE", Map.of(
                "content", "Implemented a small internal tool and reduced a manual review step."));

        assertThat(result.get("questions")).isInstanceOf(List.class);
        assertThat((List<?>) result.get("questions")).isNotEmpty();
    }
}
