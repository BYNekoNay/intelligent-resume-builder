package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.observability.AiFailureCategory;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 模型链调度的行为测试。
 *
 * <p>通过包级接缝 {@link BailianAiProvider#callChain} 注入伪调用，
 * 断言「按序尝试、失败顺延、失效冷却、无用则不试」的调度语义，
 * 全程不触碰网络。
 *
 * <p>注意：冷却随时间的到期恢复由 {@link ModelChainStateTest} 用可变时钟覆盖，
 * 这里只验证「冷却中的模型在下一次调用中被跳过」这一调度侧行为。
 */
class BailianAiProviderChainTest {

    private static final AiCallContext CTX = new AiCallContext(AiTaskType.RESUME_OPTIMIZE, Map.of());

    private AppObservability observability;

    /** 记录调用顺序的伪调用器，按剧本返回结果。 */
    private final List<String> invoked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        observability = mock(AppObservability.class);
        invoked.clear();
    }

    private BailianAiProvider providerWithChain(String chain) {
        return new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-api-key",
                "fallback-model",
                chain,
                10, 60, 1800, 60, 600,
                new ObjectMapper(), observability, new FailureCategoryClassifier());
    }

    private BailianAiProvider providerWithCooldown(String chain, long quotaCooldownSeconds) {
        return new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-api-key",
                "fallback-model",
                chain,
                10, 60, quotaCooldownSeconds, 0, 600,
                new ObjectMapper(), observability, new FailureCategoryClassifier());
    }

    private BailianAiProvider providerWithBudget(String chain, long budgetSeconds) {
        return new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-api-key",
                "fallback-model",
                chain,
                10, 60, 1800, 60, budgetSeconds,
                new ObjectMapper(), observability, new FailureCategoryClassifier());
    }

    private BailianAiProvider.ModelOutcome succeeded() {
        return new BailianAiProvider.ModelOutcome(
                AiCallResult.ok(Map.of("result", "ok"), "req-ok"),
                AiFailureCategory.NONE,
                BailianFailureClassifier.Disposition.SUCCEEDED, null);
    }

    private BailianAiProvider.ModelOutcome quotaExhausted() {
        return new BailianAiProvider.ModelOutcome(
                AiCallResult.fail("百炼 API 调用失败", true, "req-quota"),
                AiFailureCategory.QUOTA_EXHAUSTED,
                BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN,
                "AllocationQuota.FreeTierOnly");
    }

    private BailianAiProvider.ModelOutcome transportTimeout() {
        return new BailianAiProvider.ModelOutcome(
                AiCallResult.fail("百炼 API 网络异常", true, "req-timeout"),
                AiFailureCategory.TIMEOUT,
                BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN, null);
    }

    private BailianAiProvider.ModelOutcome credentialAbort() {
        return new BailianAiProvider.ModelOutcome(
                AiCallResult.fail("百炼 API 调用失败", false, "req-auth"),
                AiFailureCategory.PROVIDER_4XX,
                BailianFailureClassifier.Disposition.ABORT, "InvalidApiKey");
    }

    @Test
    @DisplayName("链首成功：只调用一次，不顺延")
    void chainHeadSuccessCallsOnce() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return succeeded();
        });

        assertTrue(result.success());
        assertEquals(List.of("m1"), invoked);
        verify(observability, never()).recordModelChainFallback(any(), any(), any());
    }

    @Test
    @DisplayName("链首额度耗尽：顺延到下一个模型并成功")
    void quotaExhaustedFallsBackToNextModel() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return "m1".equals(model) ? quotaExhausted() : succeeded();
        });

        assertTrue(result.success());
        assertEquals(List.of("m1", "m2"), invoked);
        verify(observability, times(1)).recordModelChainFallback(eq(AiTaskType.RESUME_OPTIMIZE), eq("m1"), eq("m2"));
    }

    @Test
    @DisplayName("额度耗尽的模型进入冷却：下一次调用直接跳过，不再白等一轮")
    void exhaustedModelIsSkippedOnSubsequentCall() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");

        provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return "m1".equals(model) ? quotaExhausted() : succeeded();
        });
        assertEquals(List.of("m1", "m2"), invoked);

        invoked.clear();
        AiCallResult second = provider.callChain(CTX, "req-2", model -> {
            invoked.add(model);
            return succeeded();
        });

        assertTrue(second.success());
        assertEquals(List.of("m2"), invoked, "已冷却的 m1 不应再被尝试");
    }

    @Test
    @DisplayName("链路中止类失败（如密钥无效）：立即终止，不消耗其余模型")
    void abortStopsChainImmediately() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return credentialAbort();
        });

        assertFalse(result.success());
        assertFalse(result.retryable());
        assertEquals(List.of("m1"), invoked);
    }

    @Test
    @DisplayName("瞬时故障：顺延且只做短冷却（配置 0 秒时下一次立即重试）")
    void transientFailureFallsBackWithShortCooldown() {
        BailianAiProvider provider = providerWithCooldown("m1,m2", 0);

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return transportTimeout();
        });
        assertEquals(List.of("m1", "m2"), invoked);
        assertFalse(result.success());

        invoked.clear();
        provider.callChain(CTX, "req-2", model -> {
            invoked.add(model);
            return succeeded();
        });
        assertEquals(List.of("m1"), invoked, "短冷却到期后应重新从链首尝试");
    }

    @Test
    @DisplayName("全部模型失败：返回聚合失败并说明尝试数量")
    void allModelsFailReturnsAggregateFailure() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return quotaExhausted();
        });

        assertFalse(result.success());
        assertEquals(List.of("m1", "m2", "m3"), invoked);
        assertTrue(result.errorMessage().contains("3"), "应说明已尝试的模型数量");
        assertTrue(result.errorMessage().contains("模型链"), "错误信息应指向模型链");
        verify(observability, times(2)).recordModelChainFallback(any(), any(), any());
    }

    @Test
    @DisplayName("全部模型冷却中：快速失败，一次网络调用都不发")
    void allCoolingDownFailsFastWithoutAnyAttempt() {
        BailianAiProvider provider = providerWithChain("m1,m2");

        // 第一轮把所有模型打成冷却
        provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return quotaExhausted();
        });
        assertEquals(List.of("m1", "m2"), invoked);

        invoked.clear();
        AiCallResult result = provider.callChain(CTX, "req-2", model -> {
            invoked.add(model);
            return succeeded();
        });

        assertFalse(result.success());
        assertTrue(invoked.isEmpty(), "全部冷却时不应发起任何调用");
        assertTrue(result.errorMessage().contains("冷却"), "应说明原因");
        assertTrue(result.retryable(), "应可重试，由 worker 重试机制兜底");
    }

    @Test
    @DisplayName("链总预算耗尽后停止顺延：避免单条请求长时间占用 worker 线程")
    void stopsFallingBackWhenTotalBudgetExhausted() {
        BailianAiProvider provider = providerWithBudget("m1,m2,m3", 60);
        // 每读一次时钟前进 61s，模拟第一次尝试已耗掉全部预算
        AtomicLong fakeClock = new AtomicLong(0);
        provider.useNanoTimeSupplier(() -> fakeClock.getAndAdd(Duration.ofSeconds(61).toNanos()));

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return quotaExhausted();
        });

        assertEquals(List.of("m1"), invoked, "预算耗尽后不应继续尝试 m2 / m3");
        assertFalse(result.success());
        assertTrue(result.retryable(), "应可重试，交由 worker 稍后再跑");
        assertTrue(result.errorMessage().contains("预算"),
                "错误信息应说明是预算耗尽而非模型全部失败: " + result.errorMessage());
    }

    @Test
    @DisplayName("预算充足时不影响正常的失败顺延")
    void budgetDoesNotBlockFastFallback() {
        BailianAiProvider provider = providerWithBudget("m1,m2", 600);

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return "m1".equals(model) ? quotaExhausted() : succeeded();
        });

        assertTrue(result.success(), "额度耗尽属于快速失败，预算不应阻止顺延");
        assertEquals(List.of("m1", "m2"), invoked);
    }

    @Test
    @DisplayName("模型链为空时退化为单模型，行为与改造前一致")
    void emptyChainFallsBackToSingleModel() {
        BailianAiProvider provider = providerWithChain("");

        assertEquals("fallback-model", provider.modelCode());
        assertEquals(List.of("fallback-model"), provider.modelChain());

        AiCallResult result = provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return succeeded();
        });

        assertTrue(result.success());
        assertEquals(List.of("fallback-model"), invoked);
    }

    @Test
    @DisplayName("chain 配置会做去重与空白清理")
    void chainConfigIsNormalized() {
        BailianAiProvider provider = providerWithChain(" m1 , m2 ,, m1 , m3 ");

        assertEquals(List.of("m1", "m2", "m3"), provider.modelChain());
        assertEquals("m1", provider.modelCode());
    }

    @Test
    @DisplayName("可用模型数随冷却变化，供健康检查使用")
    void availableModelCountTracksCooldown() {
        BailianAiProvider provider = providerWithChain("m1,m2,m3");
        assertEquals(3, provider.availableModelCount());

        provider.callChain(CTX, "req-1", model -> {
            invoked.add(model);
            return "m1".equals(model) ? quotaExhausted() : succeeded();
        });

        assertEquals(2, provider.availableModelCount(), "m1 冷却后可用模型数应减少");
    }

    @Test
    @DisplayName("未配置 API Key 时可用模型数为 0")
    void noApiKeyMeansNoAvailableModels() {
        BailianAiProvider provider = new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "", "m1", "m1,m2",
                10, 60, 1800, 60, 600,
                new ObjectMapper(), observability, new FailureCategoryClassifier());

        assertFalse(provider.isAvailable());
        assertEquals(0, provider.availableModelCount());
    }

    @Test
    @DisplayName("未配置 API Key 时不进入链式调度，直接返回不可重试失败")
    void noApiKeyShortCircuits() {
        BailianAiProvider provider = new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "", "m1", "m1,m2",
                10, 60, 1800, 60, 600,
                new ObjectMapper(), observability, new FailureCategoryClassifier());

        AiCallResult result = provider.call(CTX);

        assertFalse(result.success());
        assertFalse(result.retryable());
        assertTrue(result.errorMessage().contains("API Key"));
    }
}
