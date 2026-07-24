package com.intelligentresume.careermaterial.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CareerMaterialService 单元测试（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class CareerMaterialServiceTest {

    @Mock private CareerMaterialRepository repository;

    private CareerMaterialService service;

    @BeforeEach
    void setUp() {
        service = new CareerMaterialService(repository, new ObjectMapper(), 65536);
    }

    @Test
    @DisplayName("正常路径: 创建资料成功")
    void create_success() {
        when(repository.save(any(CareerMaterial.class))).thenAnswer(inv -> {
            CareerMaterial m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        CreateCareerMaterialRequest req = new CreateCareerMaterialRequest(
                MaterialType.PROJECT_EXPERIENCE, "订单系统重构",
                Map.of("role", "后端开发"), "负责订单模块重构", UsagePreference.PREFERRED);

        CareerMaterialDetail detail = service.create(req, 100L);

        assertEquals(1L, detail.id());
        assertEquals(MaterialType.PROJECT_EXPERIENCE, detail.materialType());
        assertEquals("订单系统重构", detail.title());
        assertEquals(UsagePreference.PREFERRED, detail.usagePreference());
        verify(repository).save(any(CareerMaterial.class));
    }

    @Test
    @DisplayName("边界路径: 列出空列表")
    void list_empty() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(100L)).thenReturn(List.of());

        List<CareerMaterialSummary> list = service.list(100L, null);

        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("边界路径: 按 materialType 过滤")
    void list_filterByType() {
        CareerMaterial m = material(1L, 100L, MaterialType.SKILL, "Java");
        when(repository.findByUserIdAndMaterialTypeOrderByUpdatedAtDesc(100L, MaterialType.SKILL))
                .thenReturn(List.of(m));

        List<CareerMaterialSummary> list = service.list(100L, MaterialType.SKILL);

        assertEquals(1, list.size());
        assertEquals(MaterialType.SKILL, list.get(0).materialType());
        verify(repository, never()).findByUserIdOrderByUpdatedAtDesc(anyLong());
    }

    @Test
    @DisplayName("正常路径: 创建时 usagePreference 默认 NORMAL")
    void create_defaultPreferenceIsNormal() {
        when(repository.save(any(CareerMaterial.class))).thenAnswer(inv -> {
            CareerMaterial m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        CreateCareerMaterialRequest req = new CreateCareerMaterialRequest(
                MaterialType.WORK_EXPERIENCE, "工作经历", Map.of("company", "ACME"), null, null);

        CareerMaterialDetail detail = service.create(req, 100L);

        assertEquals(UsagePreference.NORMAL, detail.usagePreference());
    }

    @Test
    @DisplayName("正常路径: 显式设置 PREFERRED/EXCLUDED 生效")
    void create_preferenceApplied() {
        when(repository.save(any(CareerMaterial.class))).thenAnswer(inv -> {
            CareerMaterial m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        CreateCareerMaterialRequest preferred = new CreateCareerMaterialRequest(
                MaterialType.SKILL, "技能", Map.of("name", "Java"), null, UsagePreference.PREFERRED);
        assertEquals(UsagePreference.PREFERRED, service.create(preferred, 100L).usagePreference());

        CreateCareerMaterialRequest excluded = new CreateCareerMaterialRequest(
                MaterialType.SKILL, "技能2", Map.of("name", "PHP"), null, UsagePreference.EXCLUDED);
        assertEquals(UsagePreference.EXCLUDED, service.create(excluded, 100L).usagePreference());
    }

    @Test
    @DisplayName("失败路径: 跨用户 get 返回 NOT_FOUND")
    void get_crossUser_notFound() {
        when(repository.findByIdAndUserId(1L, 999L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(1L, 999L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("正常路径: 软删后再 get 返回 NOT_FOUND")
    void softDelete_thenGet_notFound() {
        CareerMaterial m = material(1L, 100L, MaterialType.SKILL, "Java");
        when(repository.findByIdAndUserId(1L, 100L))
                .thenReturn(Optional.of(m))
                .thenReturn(Optional.empty());

        service.softDelete(1L, 100L);
        assertNotNull(m.getDeletedAt());

        assertThrows(BusinessException.class, () -> service.get(1L, 100L));
    }

    @Test
    @DisplayName("正常路径: 软删不级联到 history,后续版本快照仍可读")
    void softDelete_keepsHistoricalSnapshotReadable() {
        // 契约断言:softDelete 仅设置 deleted_at,不物理删除。
        // 被 resume_material_reference.source_snapshot_json 引用的资料,
        // 删除后历史快照仍可读(快照是独立 JSON 副本,不依赖源资料)。
        CareerMaterial m = material(1L, 100L, MaterialType.PROJECT_EXPERIENCE, "项目");
        when(repository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(m));

        service.softDelete(1L, 100L);

        assertNotNull(m.getDeletedAt(), "软删应设置 deletedAt");
        verify(repository).save(m);
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("失败路径: contentJson 超过 64KB 抛出 VALIDATION")
    void create_oversizedContentJson_validationFails() {
        // 构造超过 64KB 的 contentJson
        String largeValue = "x".repeat(70000);
        Map<String, Object> largeJson = Map.of("data", largeValue);

        CreateCareerMaterialRequest req = new CreateCareerMaterialRequest(
                MaterialType.SKILL, "大资料", largeJson, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    private CareerMaterial material(Long id, Long userId, MaterialType type, String title) {
        CareerMaterial m = new CareerMaterial();
        m.setId(id);
        m.setUserId(userId);
        m.setMaterialType(type);
        m.setTitle(title);
        m.setContentJson(Map.of("key", "value"));
        m.setUsagePreference(UsagePreference.NORMAL);
        return m;
    }
}
