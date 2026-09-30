package com.intelligentresume.careermaterial.service;

import com.intelligentresume.careermaterial.domain.MaterialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软校验目录的判定契约测试（三步走第一步：只告警不拒绝，见
 * docs/plans/2026-09-30-001-career-material-schema-soft-validation.md）。
 */
class CareerMaterialSchemaTest {

    @ParameterizedTest(name = "{0} 主体键 {1} 视为完整")
    @MethodSource("subjectSamples")
    @DisplayName("软校验：含任一主体键即视为完整，不告警")
    void subjectKeyPresent_isNotHalfFilled(MaterialType type, String subjectKey) {
        assertFalse(CareerMaterialSchema.isHalfFilled(type, Map.of(subjectKey, "value")));
    }

    @ParameterizedTest(name = "{0} 非主体键 {1} 视为半填")
    @MethodSource("partialSamples")
    @DisplayName("软校验：出现 schema 键但缺主体键 → 半填告警")
    void nonSubjectKnownKey_isHalfFilled(MaterialType type, String partialKey) {
        assertTrue(CareerMaterialSchema.isHalfFilled(type, Map.of(partialKey, "value")));
    }

    @Test
    @DisplayName("软校验：自由 JSON 与历史形态（无 schema 键）不告警")
    void arbitraryContent_isNotHalfFilled() {
        Map<String, Object> arbitrary = Map.of(
                "arbitraryField", "any-value",
                "count", 42,
                "nested", Map.of("anything", true));
        for (MaterialType type : MaterialType.values()) {
            assertFalse(CareerMaterialSchema.isHalfFilled(type, arbitrary), type.name());
        }
    }

    @Test
    @DisplayName("软校验：成就引导形态不告警")
    void achievementGuidanceShape_isNotHalfFilled() {
        // web/src/views/AchievementGuidanceView.vue 保存的形态：
        // {originalStatement, section, resumeVersionId, guidanceQuestions, confirmedAnswers}
        Map<String, Object> guidanceShape = Map.of(
                "originalStatement", "把响应时间降低 30%",
                "section", "work",
                "resumeVersionId", 12,
                "guidanceQuestions", java.util.List.of("背景?", "如何量化?"),
                "confirmedAnswers", java.util.List.of("峰值流量下", "P99 降低 30%"));
        assertFalse(CareerMaterialSchema.isHalfFilled(MaterialType.WORK_EXPERIENCE, guidanceShape));
        assertFalse(CareerMaterialSchema.isHalfFilled(MaterialType.PROJECT_EXPERIENCE, guidanceShape));
        assertFalse(CareerMaterialSchema.isHalfFilled(MaterialType.SKILL, guidanceShape));
    }

    @Test
    @DisplayName("软校验：null、空 map、空数组、空白字符串不计为已出现的键")
    void blankValues_areNotCountedAsPresent() {
        assertFalse(CareerMaterialSchema.isHalfFilled(MaterialType.WORK_EXPERIENCE, null));
        assertFalse(CareerMaterialSchema.isHalfFilled(MaterialType.WORK_EXPERIENCE, Map.of()));
        assertFalse(CareerMaterialSchema.isHalfFilled(
                MaterialType.WORK_EXPERIENCE, mapOf("company", "   ", "startDate", List.of())));
        assertEquals(Set.of(), CareerMaterialSchema.presentKnownKeys(
                MaterialType.WORK_EXPERIENCE, Map.of("company", "   ")));
    }

    @Test
    @DisplayName("软校验：仅有非主体键时 presentKnownKeys 返回命中的键名（供告警统计）")
    void presentKnownKeys_reportsMatchedKeysForLogging() {
        Set<String> present = CareerMaterialSchema.presentKnownKeys(MaterialType.WORK_EXPERIENCE,
                Map.of("startDate", "2025-01", "endDate", "2026-01", "company", " "));
        assertEquals(Set.of("startDate", "endDate"), present);
        assertTrue(CareerMaterialSchema.isHalfFilled(MaterialType.WORK_EXPERIENCE,
                Map.of("startDate", "2025-01", "endDate", "2026-01", "company", " ")));
    }

    @Test
    @DisplayName("软校验：已硬校验的三类不参与软校验（避免双重口径）")
    void hardValidatedTypes_areOutsideSoftSchema() {
        for (MaterialType type : new MaterialType[]{
                MaterialType.ACHIEVEMENT, MaterialType.LEADERSHIP_EXPERIENCE, MaterialType.SKILL_EVIDENCE}) {
            assertFalse(CareerMaterialSchema.isHalfFilled(type, Map.of("scenario", "a", "result", "b")), type.name());
            assertEquals(Set.of(), CareerMaterialSchema.presentKnownKeys(type, Map.of("anything", "x")));
        }
    }

    private static Stream<Arguments> subjectSamples() {
        return Stream.of(
                Arguments.of(MaterialType.WORK_EXPERIENCE, "company"),
                Arguments.of(MaterialType.WORK_EXPERIENCE, "description"),
                Arguments.of(MaterialType.PROJECT_EXPERIENCE, "name"),
                Arguments.of(MaterialType.PROJECT_EXPERIENCE, "summary"),
                Arguments.of(MaterialType.SKILL, "skillName"),
                Arguments.of(MaterialType.EDUCATION, "school"),
                Arguments.of(MaterialType.CERTIFICATE, "issuer"),
                Arguments.of(MaterialType.HIGHLIGHT, "outcome"),
                Arguments.of(MaterialType.AWARD, "title"),
                Arguments.of(MaterialType.VOLUNTEER_EXPERIENCE, "organization"),
                Arguments.of(MaterialType.COURSE, "courseName"),
                Arguments.of(MaterialType.PUBLICATION, "publisher"));
    }

    private static Stream<Arguments> partialSamples() {
        return Stream.of(
                Arguments.of(MaterialType.WORK_EXPERIENCE, "startDate"),
                Arguments.of(MaterialType.WORK_EXPERIENCE, "period"),
                Arguments.of(MaterialType.PROJECT_EXPERIENCE, "period"),
                Arguments.of(MaterialType.SKILL, "category"),
                Arguments.of(MaterialType.SKILL, "yearsOfExperience"),
                Arguments.of(MaterialType.EDUCATION, "period"),
                Arguments.of(MaterialType.CERTIFICATE, "date"),
                Arguments.of(MaterialType.HIGHLIGHT, "period"),
                Arguments.of(MaterialType.AWARD, "organization"),
                Arguments.of(MaterialType.AWARD, "date"),
                Arguments.of(MaterialType.VOLUNTEER_EXPERIENCE, "startDate"),
                Arguments.of(MaterialType.COURSE, "date"),
                Arguments.of(MaterialType.PUBLICATION, "date"));
    }

    private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }
}