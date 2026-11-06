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
 * <p>新人三问：①业务问题：生成前用户会先"预览将要发给 AI 的内容"，而这个类保证
 * 「预览看到的」和「worker 实际发出的」逐字节相同——同一份代码，不是两份手工同步的拷贝；
 * ②链路位置：上承确认快照（{@code MaterialSelectionConfirmationService} 落库的那份），
 * 下接 worker / {@code AiPreviewService}，产出 {@code _systemPrompt/_taskPrompt/_dataPrompt} 三段；
 * ③改坏它用户会看到：预览与实际发送不一致（比没有预览更糟）、或快照半写完时被静默放行。
 *
 * <p>术语：快照（snapshot）= 用户确认那一刻冻结的 JSON，后续执行只读它，不再实时查库
 * ——用户中途删资料也不会影响已经在跑的任务。
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
 *
 * <p>要点（改这里之前先看）：
 * <ul>
 *   <li>快照缺 {@code jobSnapshot}，或其中的 {@code id} 与 {@code jobDescriptionId} 不一致 → 409
 *       （CONFLICT）。这条是"半写完的快照"的唯一拦截点，别为了"容错"放宽成补查库。</li>
 *   <li>确认为空资料（{@code materialSnapshots} 为空）→ 400。生成任务必须至少有一条素材，
 *       否则模型只会凭空编造。</li>
 *   <li>{@code providerInput} 的键固定为 {@code _systemPrompt} / {@code _taskPrompt} /
 *       {@code _dataPrompt} 三段，worker 侧按这三段原样发给 provider，改名即断链。</li>
 *   <li>{@code materialsFromSnapshot} 用 {@code Enum.valueOf} 还原枚举，非法值抛的是
 *       {@code IllegalArgumentException}（不是 BusinessException），表现为 500 —— 快照里出现
 *       未知 materialType / usagePreference 说明数据已损坏，不要在这里吞异常。</li>
 *   <li>{@code promptVersion} 与 {@code JobGenerationService} 必须读同一个配置键，
 *       否则"预览显示的版本"和"实际执行的版本"会对不上。</li>
 * </ul>
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

    /** 由已确认快照重建实体并拼出 provider input；三次校验全部先于 prompt 组装，避免半成品进入 prompt。 */
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
        // 确认后的素材全部按 Fixed（MUST use）传入，preferred / candidates 两段传空（prompt 里显示为 (none)）：
        // 到这一步只剩"用户勾选的那批"，不再有优先级之分。
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
