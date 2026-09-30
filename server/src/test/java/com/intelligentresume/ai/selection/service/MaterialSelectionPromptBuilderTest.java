package com.intelligentresume.ai.selection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.generation.service.CareerMaterialAiSnapshotSanitizer;
import com.intelligentresume.ai.generation.service.MaterialPromptTextBudget;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaterialSelectionPromptBuilder 单元测试（ideation #36 预算接入）。
 *
 * <p>候选最多 60 条且 sourceText 无准入长度上限，超限必须按预算裁剪，
 * 同时保持数据段是合法 JSON、候选 materialId 全部保留。
 */
class MaterialSelectionPromptBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("候选原文超限被裁剪且 materialId 全部保留，数据段仍是合法 JSON")
    void oversizedCandidateTextClippedAndIdsPreserved() throws Exception {
        MaterialSelectionPromptBuilder builder = new MaterialSelectionPromptBuilder(
                objectMapper, new CareerMaterialAiSnapshotSanitizer(),
                new MaterialPromptTextBudget(objectMapper, 400, 1200));

        JobDescription jobDescription = new JobDescription();
        jobDescription.setId(1L);
        jobDescription.setTitle("Java后端工程师");
        jobDescription.setJdText("负责 Spring Boot 微服务开发");

        List<CareerMaterial> candidates = List.of(
                material(1L, "E".repeat(5000)),
                material(2L, "短文本"));

        MaterialSelectionPromptBuilder.Prompt prompt =
                builder.build(jobDescription, candidates, List.of(1L), Map.of());

        String payload = prompt.data().substring(prompt.data().indexOf('{'));
        Map<?, ?> data = objectMapper.readValue(payload, Map.class);
        List<?> builtCandidates = (List<?>) data.get("candidates");
        assertEquals(2, builtCandidates.size(), "裁剪不应丢弃候选资料");

        Map<?, ?> first = (Map<?, ?>) builtCandidates.get(0);
        assertEquals(1, ((Number) first.get("materialId")).intValue());
        String clipped = (String) first.get("sourceText");
        assertTrue(clipped.endsWith("[truncated]"), "超限原文应带模型可见截断标记");
        assertTrue(clipped.getBytes(StandardCharsets.UTF_8).length <= 400, "单条额度 = 400 字节");

        Map<?, ?> second = (Map<?, ?>) builtCandidates.get(1);
        assertEquals("短文本", second.get("sourceText"), "小文本应原样通过");
        assertEquals(List.of(1), data.get("forcedMaterialIds"));
    }

    private CareerMaterial material(Long id, String sourceText) {
        CareerMaterial material = new CareerMaterial();
        material.setId(id);
        material.setUserId(100L);
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setTitle("材料 " + id);
        material.setSourceText(sourceText);
        material.setUsagePreference(UsagePreference.NORMAL);
        return material;
    }
}