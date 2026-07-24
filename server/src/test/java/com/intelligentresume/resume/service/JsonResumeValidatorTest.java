package com.intelligentresume.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonResumeValidator 单元测试。
 */
class JsonResumeValidatorTest {

    private JsonResumeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonResumeValidator(
                262144,
                List.of("basics"),
                List.of("basics", "work", "education", "skills", "projects",
                        "certificates", "languages", "awards", "template"),
                new ObjectMapper());
    }

    @Test
    @DisplayName("正常路径: 合法 JSON Resume 通过校验")
    void validResume_passes() {
        Map<String, Object> json = Map.of(
                "basics", Map.of("name", "Alice"),
                "work", List.of(Map.of("company", "ACME")),
                "skills", List.of("Java", "Spring"));
        assertDoesNotThrow(() -> validator.validate(json));
    }

    @Test
    @DisplayName("正常路径: 允许保存编辑器选择的模板配置")
    void resumeWithTemplate_passes() {
        Map<String, Object> json = Map.of(
                "basics", Map.of("name", "Alice"),
                "template", Map.of("code", "classic"));

        assertDoesNotThrow(() -> validator.validate(json));
    }

    @Test
    @DisplayName("失败路径: 缺少 basics 顶层字段抛出 VALIDATION")
    void missingBasics_throwsValidation() {
        Map<String, Object> json = Map.of("work", List.of());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(json));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("basics"));
    }

    @Test
    @DisplayName("失败路径: 未知顶层字段抛出 VALIDATION")
    void unknownTopLevel_throwsValidation() {
        Map<String, Object> json = new HashMap<>();
        json.put("basics", Map.of("name", "Alice"));
        json.put("unknown_section", "value");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(json));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("unknown_section"));
    }

    @Test
    @DisplayName("边界路径: 字节数超过 256KB 抛出 VALIDATION")
    void overSize_throwsValidation() {
        // 构造一个超过 256KB 的 JSON
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300_000; i++) {
            sb.append('x');
        }
        Map<String, Object> json = Map.of(
                "basics", Map.of("name", sb.toString()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(json));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }

    @Test
    @DisplayName("边界路径: 空 JSON 抛出 VALIDATION")
    void emptyJson_throwsValidation() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(Map.of()));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }

    @Test
    @DisplayName("边界路径: null 抛出 VALIDATION")
    void nullJson_throwsValidation() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(null));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }
}
