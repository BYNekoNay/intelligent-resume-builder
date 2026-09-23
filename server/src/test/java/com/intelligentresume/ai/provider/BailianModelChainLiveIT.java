package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 模型链的真实联调测试：用真实的百炼接口验证「链首额度耗尽 → 自动顺延到可用模型」。
 *
 * <p>默认跳过，需显式开启：
 * <pre>
 * BAILIAN_LIVE_TEST=true BAILIAN_API_KEY=... mvn -Dtest=BailianModelChainLiveIT test
 * </pre>
 *
 * <p><b>前置条件</b>：{@code BAILIAN_EXHAUSTED_MODEL} 指向的模型必须处于免费额度耗尽状态
 * （百炼返回 403 {@code AllocationQuota.FreeTierOnly}），{@code BAILIAN_WORKING_MODEL} 必须可用。
 * 若账号额度状态发生变化，本测试会失败并明确提示需替换为当前真正耗尽的模型 ——
 * 这是刻意设计：它验证的是真实的失败分类与顺延行为，不能用 mock 替代。
 */
@EnabledIfEnvironmentVariable(named = "BAILIAN_LIVE_TEST", matches = "true")
class BailianModelChainLiveIT {

    @Test
    @DisplayName("链首额度耗尽：自动顺延到可用模型并成功，且耗尽模型进入冷却")
    void fallsBackFromExhaustedModelToAvailableOne() {
        String apiKey = requiredEnvironment("BAILIAN_API_KEY");
        String baseUrl = environmentOrDefault(
                "BAILIAN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String exhaustedModel = environmentOrDefault("BAILIAN_EXHAUSTED_MODEL", "qwen-plus");
        String workingModel = environmentOrDefault("BAILIAN_WORKING_MODEL", "qwen3.8-max");

        AppObservability observability = mock(AppObservability.class);
        BailianAiProvider provider = new BailianAiProvider(
                baseUrl,
                apiKey,
                workingModel,
                exhaustedModel + "," + workingModel,
                10, 120, 1800, 60,
                new ObjectMapper(), observability, new FailureCategoryClassifier());

        assertEquals(2, provider.availableModelCount(), "初始应有两个候选");

        AiCallResult result = provider.call(new AiCallContext(AiTaskType.RESUME_OPTIMIZE, Map.of(
                "content", "负责订单系统重构，QPS 从 800 提升到 5000",
                "targetJdText", "招聘 Java 后端工程师，要求熟悉 Spring 与 MySQL")));

        assertTrue(result.success(),
                "链首额度耗尽后应顺延到 " + workingModel + " 并成功，实际错误：" + result.errorMessage());
        verify(observability, times(1))
                .recordModelChainFallback(eq(AiTaskType.RESUME_OPTIMIZE), eq(exhaustedModel), eq(workingModel));
        assertEquals(1, provider.availableModelCount(),
                "额度耗尽的模型应已进入长冷却，可用候选降为 1");
    }

    @Test
    @DisplayName("链首正常时不发生顺延，可用候选保持完整")
    void noFallbackWhenHeadModelWorks() {
        String apiKey = requiredEnvironment("BAILIAN_API_KEY");
        String baseUrl = environmentOrDefault(
                "BAILIAN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String workingModel = environmentOrDefault("BAILIAN_WORKING_MODEL", "qwen3.8-max");

        AppObservability observability = mock(AppObservability.class);
        BailianAiProvider provider = new BailianAiProvider(
                baseUrl,
                apiKey,
                workingModel,
                workingModel + ",glm-5.3",
                10, 120, 1800, 60,
                new ObjectMapper(), observability, new FailureCategoryClassifier());

        AiCallResult result = provider.call(new AiCallContext(AiTaskType.RESUME_OPTIMIZE, Map.of(
                "content", "负责订单系统重构",
                "targetJdText", "招聘 Java 后端工程师")));

        assertTrue(result.success(), "链首可用时应直接成功，实际错误：" + result.errorMessage());
        verify(observability, times(0)).recordModelChainFallback(any(), any(), any());
        assertEquals(2, provider.availableModelCount(), "无顺延时候选不应减少");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
