package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.JobGenerationRequest;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaterialSelector 纯单元测试。
 * 覆盖:排除、固定保留、跨用户 NOT_FOUND、优先排序。
 */
class MaterialSelectorTest {

    private MaterialSelector selector;

    @BeforeEach
    void setUp() {
        selector = new MaterialSelector(30);
    }

    @Test
    @DisplayName("正常路径: EXCLUDED 不出现在选中")
    void exclude_removed() {
        CareerMaterial m1 = material(1L, MaterialType.WORK_EXPERIENCE);
        CareerMaterial m2 = material(2L, MaterialType.SKILL);
        CareerMaterial m3 = material(3L, MaterialType.EDUCATION);

        JobGenerationRequest req = new JobGenerationRequest(
                1L, 1L, List.of(), List.of(), List.of(2L));

        MaterialSelector.SelectionResult result = selector.select(100L, List.of(m1, m2, m3), req);

        // m2 被排除
        assertTrue(result.fixed().isEmpty());
        assertTrue(result.preferred().isEmpty());
        assertEquals(1, result.excluded().size());
        assertEquals(2L, result.excluded().get(0).getId());
        // m1, m3 在 normal 中
        assertEquals(2, result.normal().size());
        assertTrue(result.normal().stream().noneMatch(m -> m.getId() == 2L));
        // unselectedReasons 包含 m2
        assertEquals("USER_EXCLUDED", result.unselectedReasons().get(2L));
    }

    @Test
    @DisplayName("正常路径: INCLUDED 必须保留")
    void include_retained() {
        CareerMaterial m1 = material(1L, MaterialType.WORK_EXPERIENCE);
        CareerMaterial m2 = material(2L, MaterialType.SKILL);

        JobGenerationRequest req = new JobGenerationRequest(
                1L, 1L, List.of(1L), List.of(), List.of());

        MaterialSelector.SelectionResult result = selector.select(100L, List.of(m1, m2), req);

        assertEquals(1, result.fixed().size());
        assertEquals(1L, result.fixed().get(0).getId());
        // m2 在 normal 中
        assertEquals(1, result.normal().size());
        assertEquals(2L, result.normal().get(0).getId());
    }

    @Test
    @DisplayName("失败路径: 跨用户 materialId 抛 NOT_FOUND")
    void crossUserMaterial_notFound() {
        CareerMaterial m1 = material(1L, MaterialType.WORK_EXPERIENCE);

        // includedMaterialIds 包含 999, 但 allMaterials 中没有
        JobGenerationRequest req = new JobGenerationRequest(
                1L, 1L, List.of(999L), List.of(), List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> selector.select(100L, List.of(m1), req));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("正常路径: PREFERRED 排在 normal 之前")
    void preferredOrderFirst() {
        CareerMaterial m1 = material(1L, MaterialType.WORK_EXPERIENCE);
        CareerMaterial m2 = material(2L, MaterialType.SKILL);
        CareerMaterial m3 = material(3L, MaterialType.EDUCATION);

        JobGenerationRequest req = new JobGenerationRequest(
                1L, 1L, List.of(), List.of(3L), List.of());

        MaterialSelector.SelectionResult result = selector.select(100L, List.of(m1, m2, m3), req);

        assertEquals(1, result.preferred().size());
        assertEquals(3L, result.preferred().get(0).getId());
        // normal 不包含 m3
        assertEquals(2, result.normal().size());
        assertTrue(result.normal().stream().noneMatch(m -> m.getId() == 3L));
    }

    private CareerMaterial material(Long id, MaterialType type) {
        CareerMaterial m = new CareerMaterial();
        m.setId(id);
        m.setUserId(100L);
        m.setMaterialType(type);
        m.setTitle("材料 " + id);
        m.setContentJson(Map.of("key", "value"));
        m.setUsagePreference(UsagePreference.NORMAL);
        return m;
    }
}
