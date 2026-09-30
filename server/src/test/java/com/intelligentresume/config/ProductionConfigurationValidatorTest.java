package com.intelligentresume.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationValidatorTest {

    @Test
    void acceptsDistinctProductionSecrets() {
        assertDoesNotThrow(() -> new ProductionConfigurationValidator(
                "a".repeat(48), "b".repeat(48), "c".repeat(24), "sk-real-bailian-key", true, true).validate());
    }

    @Test
    void rejectsDevelopmentJwtSecret() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "dev-only-change-me-please-32bytes-min-length-required", "b".repeat(48), "c".repeat(24), "sk-real-bailian-key", true, true);

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void rejectsInsecureRefreshCookie() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "a".repeat(48), "b".repeat(48), "c".repeat(24), "sk-real-bailian-key", false, true);

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void rejectsRepositoryPlaceholderSecrets() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "replace-with-at-least-32-random-characters", "b".repeat(48), "c".repeat(24), "replace-with-your-bailian-key", true, true);

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void rejectsUntrustedForwardedHeaders() {
        // 生产流量经代理到达 API：不信任转发头会让所有客户端共用代理地址这一个限流分桶
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "a".repeat(48), "b".repeat(48), "c".repeat(24), "sk-real-bailian-key", true, false);

        assertThrows(IllegalStateException.class, validator::validate);
    }
}
