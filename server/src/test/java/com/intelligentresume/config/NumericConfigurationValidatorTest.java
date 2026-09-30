package com.intelligentresume.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值型运维配置校验单测：每个受校验的键取「最小值 − 1」都必须在启动时失败，
 * 取最小值本身必须放行；键全部缺省时校验自身失败（防解析路径失效后空转）。
 */
class NumericConfigurationValidatorTest {

    @Test
    @DisplayName("每个键取「最小值 − 1」都 fail-closed，且异常点明键名")
    void rejectsEveryValueBelowMinimum() {
        assertTrue(NumericConfigurationValidator.minimums().size() >= 14,
                "受校验的键过少，门禁可能失效（当前 " + NumericConfigurationValidator.minimums().size() + "）");

        NumericConfigurationValidator.minimums().forEach((key, minimum) -> {
            MockEnvironment environment = validEnvironment();
            environment.setProperty(key, String.valueOf(minimum - 1));

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> new NumericConfigurationValidator(environment).validate(),
                    key + " 取 " + (minimum - 1) + " 应在启动时被拒");
            assertTrue(exception.getMessage().contains(key),
                    "异常应点明非法键：" + exception.getMessage());
        });
    }

    @Test
    @DisplayName("取最小值本身放行（不做过度收紧）")
    void acceptsMinimumValues() {
        assertDoesNotThrow(() -> new NumericConfigurationValidator(validEnvironment()).validate());
    }

    @Test
    @DisplayName("键未解析时校验自身失败：防配置解析路径失效后门禁静默空转")
    void failsClosedWhenKeysDoNotResolve() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new NumericConfigurationValidator(new MockEnvironment()).validate());
        assertTrue(exception.getMessage().contains("未解析"), exception.getMessage());
    }

    private MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        NumericConfigurationValidator.minimums()
                .forEach((key, minimum) -> environment.setProperty(key, String.valueOf(minimum)));
        return environment;
    }
}
