package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaterialPromptTextBudget 单元测试（ideation #36）。
 *
 * <p>契约：不丢弃资料（materialId 恒保留）；单条额度 = min(单条上限, 总量/条数)；
 * 截断带模型可见标记；CJK 按 UTF-8 字符边界截断；contentJson 裁剪后仍是合法 JSON。
 */
class MaterialPromptTextBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MaterialPromptTextBudget budget(int perMaterialBytes, int totalBytes) {
        return new MaterialPromptTextBudget(objectMapper, perMaterialBytes, totalBytes);
    }

    private CareerMaterial material(Long id, String sourceText, Map<String, Object> contentJson) {
        CareerMaterial material = new CareerMaterial();
        material.setId(id);
        material.setUserId(100L);
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setTitle("材料 " + id);
        material.setUsagePreference(UsagePreference.NORMAL);
        material.setSourceText(sourceText);
        material.setContentJson(contentJson);
        return material;
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("小文本原样通过：不截断、不标记")
    void smallTextPassesThroughUnchanged() {
        List<CareerMaterial> input = List.of(
                material(1L, "负责订单平台重构", Map.of("company", "星河科技")),
                material(2L, "short", Map.of()));

        List<CareerMaterial> result = budget(1024, 8192).clip(input);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("负责订单平台重构", result.get(0).getSourceText());
        assertEquals(Map.of("company", "星河科技"), result.get(0).getContentJson());
        assertFalse(result.get(0).getSourceText().contains(MaterialPromptTextBudget.TRUNCATION_MARKER));
        assertEquals("short", result.get(1).getSourceText());
    }

    @Test
    @DisplayName("单条来源文本超限：截断到额度并追加 [truncated] 标记")
    void oversizedSourceTextClippedAndMarked() {
        List<CareerMaterial> result = budget(120, 1024).clip(List.of(material(1L, "A".repeat(600), null)));

        String clipped = result.get(0).getSourceText();
        assertTrue(clipped.startsWith("A"));
        assertTrue(clipped.endsWith(MaterialPromptTextBudget.TRUNCATION_MARKER));
        assertTrue(bytes(clipped) <= 120, "单条文本字节数不应超过额度");
    }

    @Test
    @DisplayName("多条资料共享总量预算：单条额度 = 总量/条数，总量不超上限且资料不丢弃")
    void manyMaterialsShareTotalBudget() {
        List<CareerMaterial> input = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            input.add(material(id, "B".repeat(500), null));
        }

        List<CareerMaterial> result = budget(100_000, 600).clip(input);

        assertEquals(6, result.size(), "裁剪不应丢弃资料");
        long total = 0;
        for (CareerMaterial material : result) {
            int size = bytes(material.getSourceText());
            assertTrue(size <= 100, "单条额度 = 600/6 = 100 字节");
            assertTrue(material.getSourceText().endsWith(MaterialPromptTextBudget.TRUNCATION_MARKER));
            total += size;
        }
        assertTrue(total <= 600, "资料文本总量不应超过总量上限");
    }

    @Test
    @DisplayName("contentJson 超限：按顶层条目保留、置 _truncated 标记，序列化仍是合法 JSON")
    void oversizedContentJsonSlimmedToValidJson() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("company", "星河科技");
        content.put("huge", "C".repeat(4000));

        List<CareerMaterial> result = budget(200, 200).clip(List.of(material(1L, null, content)));

        Map<?, ?> clipped = result.get(0).getContentJson();
        assertEquals("星河科技", clipped.get("company"));
        assertEquals(Boolean.TRUE, clipped.get("_truncated"));
        assertFalse(clipped.containsKey("huge"), "放不下的条目整条省略，不切断字符串");
        assertNotNull(objectMapper.readValue(objectMapper.writeValueAsString(clipped), Map.class));
    }

    @Test
    @DisplayName("contentJson 占满额度时来源文本让位为空，不留无意义前缀")
    void contentJsonConsumesAllowance() {
        List<CareerMaterial> result = budget(36, 36)
                .clip(List.of(material(1L, "D".repeat(100), Map.of("company", "星河科技"))));

        assertEquals(Map.of("company", "星河科技"), result.get(0).getContentJson());
        assertEquals("", result.get(0).getSourceText());
    }

    @Test
    @DisplayName("CJK 截断落在 UTF-8 字符边界：不产生半个字符或替换符")
    void cjkClippedOnCharacterBoundary() {
        List<CareerMaterial> result = budget(60, 60).clip(List.of(material(1L, "中".repeat(50), null)));

        String clipped = result.get(0).getSourceText();
        // 额度 60 字节，标记占 12 字节 → 前缀 48 字节 = 16 个「中」
        assertEquals("中".repeat(16) + MaterialPromptTextBudget.TRUNCATION_MARKER, clipped);
        assertFalse(clipped.contains("\uFFFD"));
    }

    @Test
    @DisplayName("空文本资料仍保留在结果中（materialId 不丢）")
    void blankMaterialStillPresent() {
        List<CareerMaterial> result = budget(64, 64).clip(List.of(material(9L, null, null)));

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getId());
        assertNull(result.get(0).getSourceText());
        assertNull(result.get(0).getContentJson());
    }
}