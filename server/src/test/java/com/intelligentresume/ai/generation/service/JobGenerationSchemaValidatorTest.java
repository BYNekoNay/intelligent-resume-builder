package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobGenerationSchemaValidator 纯单元测试。
 * 覆盖:合法草稿、未知顶层字段、缺少 _source/_pending、字节超限。
 */
class JobGenerationSchemaValidatorTest {

    private JobGenerationSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JobGenerationSchemaValidator(65536, new ObjectMapper());
    }

    @Test
    @DisplayName("正常路径: 合法草稿(含 _source 与 _pending)通过")
    void validDraft_passes() {
        Map<String, Object> draft = Map.of(
                "basics", Map.of(
                        "_source", Map.of("materialId", 1, "path", "basics"),
                        "name", "测试用户"),
                "work", List.of(
                        Map.of("_source", Map.of("materialId", 1, "path", "work[0]"),
                                "company", "测试公司"),
                        Map.of("_pending", Map.of("reason", "缺少信息"),
                                "company", "待补充"))
        );

        assertDoesNotThrow(() -> validator.validate(draft, "v1.0.0"));
    }

    @Test
    @DisplayName("失败路径: 顶层未知字段 'foo' 抛出")
    void unknownTopLevel_fails() {
        Map<String, Object> draft = Map.of(
                "foo", Map.of("bar", "baz"),
                "basics", Map.of("_source", Map.of("materialId", 1, "path", "basics"))
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(draft, "v1.0.0"));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("foo"));
    }

    @Test
    @DisplayName("失败路径: 数组元素同时缺 _source 与 _pending 抛出")
    void missingBothSourceAndPending_fails() {
        Map<String, Object> draft = Map.of(
                "work", List.of(
                        Map.of("company", "无来源标记的公司"))
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(draft, "v1.0.0"));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }

    @Test
    @DisplayName("failure: source outside confirmed material snapshot is rejected")
    void sourceOutsideConfirmedSnapshot_fails() {
        Map<String, Object> draft = Map.of(
                "basics", Map.of("_sources", List.of(Map.of("materialId", 2L)), "name", "Candidate"),
                "work", List.of(Map.of("_sources", List.of(Map.of("materialId", 2L)), "company", "Example"))
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate(draft, "v1.0.0", Set.of(1L)));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("confirmed material snapshot"));
    }

    @Test
    @DisplayName("success: source inside confirmed material snapshot is accepted")
    void sourceInsideConfirmedSnapshot_passes() {
        Map<String, Object> draft = Map.of(
                "basics", Map.of("_sources", List.of(Map.of("materialId", 1L)), "name", "Candidate"),
                "work", List.of(Map.of("_sources", List.of(Map.of("materialId", 1L)), "company", "Example"))
        );

        assertDoesNotThrow(() -> validator.validate(draft, "v1.0.0", Set.of(1L)));
    }

    @Test
    void basicsMayUseConfirmedPersonalProfileWithoutMaterialProvenance() {
        Map<String, Object> draft = Map.of(
                "basics", Map.of("summary", "Backend engineer focused on reliable services"),
                "work", List.of(Map.of("_sources", List.of(Map.of("materialId", 1L)), "company", "Example"))
        );

        assertDoesNotThrow(() -> validator.validate(draft, "v1.0.0", Set.of(1L)));
    }

    @Test
    @DisplayName("正常路径: 新增内容模块携带来源时通过")
    void extendedResumeSections_pass() {
        Map<String, Object> draft = Map.of(
                "objective", Map.of("summary", "Target role summary"),
                "volunteering", List.of(Map.of("organization", "Community", "_sources", List.of(Map.of("materialId", 1L)))),
                "courses", List.of(Map.of("name", "Cloud course", "_sources", List.of(Map.of("materialId", 1L)))),
                "publications", List.of(Map.of("title", "Paper", "_sources", List.of(Map.of("materialId", 1L)))),
                "customSections", List.of(Map.of("title", "Leadership", "_sources", List.of(Map.of("materialId", 1L))))
        );

        assertDoesNotThrow(() -> validator.validate(draft, "v1.0.0", Set.of(1L)));
    }

    @Test
    @DisplayName("失败路径: 字节数超过 max 抛出")
    void overSize_fails() {
        // 使用极小的 maxOutputBytes
        JobGenerationSchemaValidator tinyValidator =
                new JobGenerationSchemaValidator(10, new ObjectMapper());

        Map<String, Object> draft = Map.of(
                "basics", Map.of("_source", Map.of("materialId", 1, "path", "basics"),
                        "name", "这是一个很长的名字用来测试字节数超限的情况")
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tinyValidator.validate(draft, "v1.0.0"));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("字节"));
    }
}
