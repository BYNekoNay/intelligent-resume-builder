package com.intelligentresume.ai.generation.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JOB_GENERATION 的 provider input 组装 —— worker 与发送前预览**共用同一实现**（R32 AC-R32-8）。
 *
 * <p>为什么必须抽出来：{@code executeTask} 的快照分支原本在方法体内就地重建实体、拼三段式 prompt
 * 并装成 {@code _systemPrompt/_taskPrompt/_dataPrompt}。预览如果自己再拼一遍，
 * 两份实现必然漂移，用户看到的和实际发出去的就会悄悄不一样 —— 这比没有预览更糟。
 *
 * <p>入参是**已确认子任务的快照**（{@code jobSnapshot} / {@code materialSnapshots} /
 * {@code personalProfileSnapshot}）：worker 用落库的那份，预览用
 * {@code MaterialSelectionConfirmationService#buildGenerationInput} 刚组装出来、**即将落库**的那份，
 * 因此两边拿到的字节完全相同。
 *
 * <p>本类**只读**：不写库、不调 provider。
 */
@Component
public class JobGenerationInputAssembler {

    private final JobGenerationPromptBuilder promptBuilder;
    private final String promptVersion;

    /**
     * @param promptVersion 与 {@code JobGenerationService} 读同一个配置键（{@code app.ai.generation.prompt-version}），
     *                      否则预览与 worker 会拿到不同版本的 prompt 文本
     */
    public JobGenerationInputAssembler(
            JobGenerationPromptBuilder promptBuilder,
            @Value("${app.ai.generation.prompt-version:v1.0.0}") String promptVersion) {
        this.promptBuilder = promptBuilder;
        this.promptVersion = promptVersion;
    }

    /** 一次组装的全部产物：{@code providerInput} 用于预览与发送，其余供 worker 做校验与结果统计。 */
    public record GenerationInput(Map<String, Object> providerInput,
                                  JobDescription job,
                                  List<CareerMaterial> materials,
                                  Map<String, Object> profile) {
    }

    public GenerationInput fromSnapshot(Map<String, Object> snapshot) {
        Map<String, Object> safeSnapshot = snapshot != null ? snapshot : Map.of();
        JobDescription job = jobFromSnapshot(safeSnapshot.get("jobSnapshot"));
        List<CareerMaterial> materials = materialsFromSnapshot(safeSnapshot.get("materialSnapshots"));
        Map<String, Object> profile = mapValue(safeSnapshot.get("personalProfileSnapshot"));
        Long jobId = toLong(safeSnapshot.get("jobDescriptionId"));
        if (job == null || !Objects.equals(job.getId(), jobId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Generation task has no confirmed job snapshot");
        }
        if (materials.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "No confirmed career materials");
        }
        JobGenerationPromptBuilder.Prompt prompt = promptBuilder.build(job, materials, List.of(), List.of(),
                careerProfileContext(profile), promptVersion);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("_systemPrompt", prompt.system());
        providerInput.put("_taskPrompt", prompt.task());
        providerInput.put("_dataPrompt", prompt.data());
        return new GenerationInput(providerInput, job, materials, profile);
    }

    public String promptVersion() {
        return promptVersion;
    }

    // ------------------------------------------------------------------ 内部

    private JobDescription jobFromSnapshot(Object raw) {
        Map<String, Object> value = mapValue(raw);
        if (value.isEmpty()) return null;
        JobDescription job = new JobDescription();
        job.setId(toLong(value.get("id")));
        job.setTitle(stringValue(value.get("title")));
        job.setCompanyName(stringValue(value.get("companyName")));
        job.setJdText(stringValue(value.get("jdText")));
        return job;
    }

    private List<CareerMaterial> materialsFromSnapshot(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<CareerMaterial> materials = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = mapValue(item);
            if (value.isEmpty()) continue;
            CareerMaterial material = new CareerMaterial();
            material.setId(toLong(value.get("id")));
            material.setTitle(stringValue(value.get("title")));
            material.setMaterialType(MaterialType.valueOf(stringValue(value.get("materialType"))));
            material.setUsagePreference(UsagePreference.valueOf(stringValue(value.get("usagePreference"))));
            material.setSourceText(stringValue(value.get("sourceText")));
            material.setContentJson(mapValue(value.get("contentJson")));
            materials.add(material);
        }
        return materials;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    /**
     * 只挑职业定位类字段：联系方式不进 prompt，模型只拿到与岗位匹配有关的事实（R32 默认排除联系方式）。
     */
    private Map<String, Object> careerProfileContext(Map<String, Object> profile) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("profileSummary", "targetRoleTitles", "targetSeniority", "targetIndustries",
                "targetWorkPreferences", "careerPositioningSummary")) {
            Object value = profile.get(key);
            if (value instanceof Collection<?> collection && collection.isEmpty()) continue;
            if (value != null && !value.toString().isBlank()) context.put(key, value);
        }
        return context;
    }

    private String stringValue(Object value) { return value == null ? null : value.toString(); }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
