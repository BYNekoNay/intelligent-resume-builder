package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.prompt.PromptBuilder;
import com.intelligentresume.ai.generation.validator.JobGenerationSchemaValidator;
import com.intelligentresume.ai.generation.validator.PromptInjectionDetector;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 岗位定制生成。
 *
 * <p>严禁本服务创建 {@code resume_version};草稿存于 {@code ai_task.result_json},
 * 经 T08 用户逐项确认后才创建新版本。
 */
@Service
public class JobGenerationService {

    private final AiTaskRepository taskRepository;
    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final MaterialSelector materialSelector;
    private final PromptBuilder promptBuilder;
    private final PromptInjectionDetector injectionDetector;
    private final JobGenerationSchemaValidator schemaValidator;
    private final AiProvider aiProvider;
    private final boolean localValidationFailureInjectionEnabled;

    public JobGenerationService(AiTaskRepository taskRepository,
                                CareerMaterialRepository materialRepository,
                                JobDescriptionRepository jobDescriptionRepository,
                                MaterialSelector materialSelector,
                                PromptBuilder promptBuilder,
                                PromptInjectionDetector injectionDetector,
                                JobGenerationSchemaValidator schemaValidator,
                                AiProvider aiProvider,
                                @Value("${app.local-validation.failure-injection-enabled:false}") boolean localValidationFailureInjectionEnabled) {
        this.taskRepository = taskRepository;
        this.materialRepository = materialRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.materialSelector = materialSelector;
        this.promptBuilder = promptBuilder;
        this.injectionDetector = injectionDetector;
        this.schemaValidator = schemaValidator;
        this.aiProvider = aiProvider;
        this.localValidationFailureInjectionEnabled = localValidationFailureInjectionEnabled;
    }

    @Transactional
    public void run(Long taskId) {
        AiTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (task.getStatus() != AiTask.TaskStatus.PENDING
                && task.getStatus() != AiTask.TaskStatus.RUNNING
                && task.getStatus() != AiTask.TaskStatus.FAILED) {
            return;
        }
        if (!AiTask.TaskType.JOB_GENERATION.name().equals(task.getTaskType().name())) {
            return;
        }

        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务 input_snapshot_json 缺失");
        }

        // 1. 加载 JD,过注入检测
        if (shouldInjectLocalValidationFailure(snapshot, task)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Synthetic local validation failure before retry");
        }
        Object jdIdRaw = snapshot.get("jobDescriptionId");
        if (!(jdIdRaw instanceof Number jdIdNum)) {
            throw new BusinessException(ErrorCode.VALIDATION, "input_snapshot_json.jobDescriptionId 缺失");
        }
        JobDescription jd = jobDescriptionRepository.findByIdAndUserId(jdIdNum.longValue(), task.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
        injectionDetector.assertSafe(jd.getJdText());

        // 2. 加载资料并按用户偏好筛选
        List<CareerMaterial> all = materialRepository.findByUserIdOrderByUpdatedAtDesc(task.getUserId());
        Set<Long> included = toIdSet(snapshot.get("includedMaterialIds"));
        Set<Long> preferred = toIdSet(snapshot.get("preferredMaterialIds"));
        Set<Long> excluded = toIdSet(snapshot.get("excludedMaterialIds"));
        MaterialSelector.SelectedMaterials picked = materialSelector.select(all, preferred, included, excluded);

        // 3. 构造 prompt 并调用 Provider
        Map<String, Object> input = new HashMap<>();
        input.put("jdText", jd.getJdText());
        input.put("materials", picked.selected());
        input.put("unselectedMaterials", picked.unselected());
        input.put("parsedKeywords", jd.getParsedKeywordsJson());
        String prompt = promptBuilder.buildJobGenerationPrompt(input);

        Map<String, Object> rawDraft = new LinkedHashMap<>(aiProvider.invoke("JOB_GENERATION",
                Map.of("prompt", prompt,
                        "userId", String.valueOf(task.getUserId()),
                        "materials", picked.selected(),
                        "unselectedMaterials", picked.unselected())));
        rawDraft.put("selected", describeMaterials(picked.selected(), picked.reasons(), "SELECTED"));
        rawDraft.put("unselected", describeMaterials(picked.unselected(), picked.reasons(), "UNSELECTED"));
        rawDraft.putIfAbsent("missing", picked.selected().isEmpty()
                ? List.of("没有可用于生成的职业资料，请先补充或调整资料选择设置。")
                : List.of());
        rawDraft.putIfAbsent("warnings", List.of());

        // 4. 校验输出 schema(每条事实必须有 _source / _pending)
        schemaValidator.validate(rawDraft);

        // 5. 写回 ai_task.result_json(updated_at 由 @LastModifiedDate 自动维护)
        task.setStatus(AiTask.TaskStatus.SUCCESS);
        task.setLeaseExpiresAt(null);
        task.setResultJson(rawDraft);
        taskRepository.save(task);
    }

    private Set<Long> toIdSet(Object raw) {
        Set<Long> set = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) set.add(n.longValue());
                else if (o instanceof String s) {
                    try { set.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return set;
    }

    private boolean shouldInjectLocalValidationFailure(Map<String, Object> snapshot, AiTask task) {
        if (!localValidationFailureInjectionEnabled || task.getRetryCount() != 0) return false;
        Object additionalInput = snapshot.get("additionalInput");
        if (!(additionalInput instanceof Map<?, ?> values)) return false;
        return values.get("localValidationFailOnce") instanceof Boolean enabled && enabled;
    }

    private List<Map<String, Object>> describeMaterials(List<CareerMaterial> materials,
                                                         List<String> reasons,
                                                         String status) {
        return materials.stream().map(material -> {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("materialId", material.getId());
            description.put("title", material.getTitle());
            description.put("materialType", material.getMaterialType().name());
            description.put("status", status);
            description.put("reason", reasons.stream()
                    .filter(reason -> reason.contains(": " + material.getId() + " ("))
                    .findFirst()
                    .orElse(status));
            return description;
        }).toList();
    }
}
