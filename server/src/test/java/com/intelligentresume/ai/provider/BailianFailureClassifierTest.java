package com.intelligentresume.ai.provider;

import com.intelligentresume.common.observability.AiFailureCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 百炼失败分类与链式调度动作判定测试。
 *
 * <p>夹具取自线上真实响应形状：额度耗尽时百炼返回 403 +
 * {@code {"error":{"code":"AllocationQuota.FreeTierOnly","message":"..."}}}。
 */
class BailianFailureClassifierTest {

    private static final String QUOTA_BODY =
            "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\","
                    + "\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, ...\"}}";
    private static final String INVALID_KEY_BODY =
            "{\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\"}}";
    private static final String MODEL_NOT_FOUND_BODY =
            "{\"error\":{\"code\":\"Model.NotFound\",\"message\":\"model not exist\"}}";

    @Test
    @DisplayName("提取 error.code")
    void extractsErrorCode() {
        assertEquals("AllocationQuota.FreeTierOnly", BailianFailureClassifier.extractErrorCode(QUOTA_BODY));
    }

    @Test
    @DisplayName("没有 code 时退回 error.type")
    void fallsBackToErrorType() {
        assertEquals("rate_limit_error",
                BailianFailureClassifier.extractErrorCode("{\"error\":{\"type\":\"rate_limit_error\"}}"));
    }

    @Test
    @DisplayName("空响应体返回 null 且不抛异常")
    void blankBodyReturnsNull() {
        assertNull(BailianFailureClassifier.extractErrorCode(null));
        assertNull(BailianFailureClassifier.extractErrorCode(""));
        assertNull(BailianFailureClassifier.extractErrorCode("   "));
        assertNull(BailianFailureClassifier.extractErrorCode("not json at all"));
    }

    @Test
    @DisplayName("额度耗尽：403 被正确归类为 QUOTA_EXHAUSTED 而非笼统的 PROVIDER_4XX")
    void quotaCodeBeatsGenericStatusMapping() {
        assertEquals(AiFailureCategory.QUOTA_EXHAUSTED,
                BailianFailureClassifier.categoryFor(403, "AllocationQuota.FreeTierOnly"));
    }

    @Test
    @DisplayName("密钥无效仍归类为 PROVIDER_4XX")
    void invalidApiKeyStaysProvider4xx() {
        assertEquals(AiFailureCategory.PROVIDER_4XX,
                BailianFailureClassifier.categoryFor(403, "InvalidApiKey"));
    }

    @Test
    @DisplayName("限流与服务端错误分类")
    void rateLimitAndServerError() {
        assertEquals(AiFailureCategory.RATE_LIMITED, BailianFailureClassifier.categoryFor(429, null));
        assertEquals(AiFailureCategory.RATE_LIMITED,
                BailianFailureClassifier.categoryFor(400, "Throttling.RateQuota"));
        assertEquals(AiFailureCategory.PROVIDER_5XX, BailianFailureClassifier.categoryFor(503, null));
    }

    @Test
    @DisplayName("限流码含 Quota 字样时不得被误判为额度耗尽")
    void throttlingCodeContainingQuotaIsNotTreatedAsExhausted() {
        // 回归测试：Throttling.RateQuota 含 "quota" 子串，曾被误判为额度耗尽，
        // 后果是把瞬时限流的模型白关 30 分钟。
        assertFalse(BailianFailureClassifier.isQuotaExhausted("Throttling.RateQuota"));
        assertTrue(BailianFailureClassifier.isThrottling("Throttling.RateQuota"));

        assertEquals(AiFailureCategory.RATE_LIMITED,
                BailianFailureClassifier.categoryFor(429, "Throttling.RateQuota"));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionFor(429, "Throttling.RateQuota"));
    }

    @Test
    @DisplayName("额度耗尽顺延并长冷却")
    void quotaExhaustedFallsThroughWithLongCooldown() {
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN,
                BailianFailureClassifier.dispositionFor(403, "AllocationQuota.FreeTierOnly"));
    }

    @Test
    @DisplayName("密钥无效立即终止，不在链上浪费其余模型的额度")
    void invalidApiKeyAbortsChain() {
        assertEquals(BailianFailureClassifier.Disposition.ABORT,
                BailianFailureClassifier.dispositionFor(401, "InvalidApiKey"));
        assertEquals(BailianFailureClassifier.Disposition.ABORT,
                BailianFailureClassifier.dispositionFor(403, "InvalidApiKey"));
    }

    @Test
    @DisplayName("模型不存在：顺延并长冷却（该模型已不可用，重试无意义）")
    void modelNotFoundFallsThroughWithLongCooldown() {
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN,
                BailianFailureClassifier.dispositionFor(404, "Model.NotFound"));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN,
                BailianFailureClassifier.dispositionFor(404, null));
    }

    @Test
    @DisplayName("限流与 5xx 顺延但只做短冷却")
    void throttleAndServerErrorUseShortCooldown() {
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionFor(429, null));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionFor(500, null));
    }

    @Test
    @DisplayName("其它 4xx 立即终止：很可能是我们自己的请求体有问题，顺延只会重复消耗额度")
    void otherClientErrorsAbort() {
        assertEquals(BailianFailureClassifier.Disposition.ABORT,
                BailianFailureClassifier.dispositionFor(400, "InvalidParameter"));
        assertEquals(BailianFailureClassifier.Disposition.ABORT,
                BailianFailureClassifier.dispositionFor(422, null));
    }

    @Test
    @DisplayName("传输层与响应结构异常的处置映射")
    void transportCategoriesMapToDispositions() {
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.TIMEOUT));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.CONNECTION));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_SHORT_COOLDOWN,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.PROVIDER_RESPONSE_INVALID));
        assertEquals(BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.QUOTA_EXHAUSTED));
        assertEquals(BailianFailureClassifier.Disposition.ABORT,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.CONSENT_REVOKED));
        assertEquals(BailianFailureClassifier.Disposition.SUCCEEDED,
                BailianFailureClassifier.dispositionForCategory(AiFailureCategory.NONE));
    }

    @Test
    @DisplayName("isChainable 与处置判定一致")
    void isChainableMatchesDisposition() {
        assertTrue(BailianFailureClassifier.isChainable(AiFailureCategory.TIMEOUT));
        assertTrue(BailianFailureClassifier.isChainable(AiFailureCategory.QUOTA_EXHAUSTED));
        assertFalse(BailianFailureClassifier.isChainable(AiFailureCategory.CONSENT_REVOKED));
        assertFalse(BailianFailureClassifier.isChainable(AiFailureCategory.SCHEMA_INVALID));
    }

    @Test
    @DisplayName("额度关键字识别（含欠费等变体）")
    void quotaKeywordDetection() {
        assertTrue(BailianFailureClassifier.isQuotaExhausted("AllocationQuota.FreeTierOnly"));
        assertTrue(BailianFailureClassifier.isQuotaExhausted("Arrearage"));
        assertTrue(BailianFailureClassifier.isQuotaExhausted("InsufficientBalance"));
        assertFalse(BailianFailureClassifier.isQuotaExhausted("InvalidApiKey"));
        assertFalse(BailianFailureClassifier.isQuotaExhausted(null));
    }

    @Test
    @DisplayName("响应体中的非错误码内容不会被带出（隐私约束）")
    void extractedValueIsOnlyTheCode() {
        String bodyWithEchoedContent = "{\"error\":{\"code\":\"InvalidParameter\","
                + "\"message\":\"简历里出现了张三的邮箱 zhangsan@example.com\"}}";

        String code = BailianFailureClassifier.extractErrorCode(bodyWithEchoedContent);

        assertEquals("InvalidParameter", code);
        assertFalse(code.contains("zhangsan"), "提取结果不得包含响应体中的业务内容");
    }
}
