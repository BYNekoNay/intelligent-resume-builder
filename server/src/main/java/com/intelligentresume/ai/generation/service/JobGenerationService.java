package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.*;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 岗位定制生成服务。编排资料选择、Prompt 构建、AI 调用、Schema 校验。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>不创建 resume_version(留给 T08)</li>
 *   <li>成功后 confirmation_status=PENDING</li>
 *   <li>跨用户资料/简历/JD → NOT_FOUND(不泄露存在性)</li>
 *   <li>Prompt Injection 警告但不阻止</li>
 * </ul>
 */
@Service
public class JobGenerationService {

    private static final Logger log = LoggerFactory.getLogger(JobGenerationService.class);
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jdRepository;
    private final ResumeRepository resumeRepository;
    private final AiTaskRepository taskRepository;
    private final MaterialSelector materialSelector;
    private final PromptInjectionDetector injectionDetector;
    private final JobGenerationPromptBuilder promptBuilder;
    private final JobGenerationSchemaValidator schemaValidator;
    private final AiProviderRegistry providerRegistry;

    @Value("${app.ai.generation.prompt-version:v1.0.0}")
    private String promptVersion;

    @Value("${app.ai.generation.schema-version:v1.0.0}")
    private String schemaVersion;

    public JobGenerationService(CareerMaterialRepository materialRepository,
                                JobDescriptionRepository jdRepository,
                                ResumeRepository resumeRepository,
                                AiTaskRepository taskRepository,
                                MaterialSelector materialSelector,
                                PromptInjectionDetector injectionDetector,
                                JobGenerationPromptBuilder promptBuilder,
                                JobGenerationSchemaValidator schemaValidator,
                                AiProviderRegistry providerRegistry) {
        this.materialRepository = materialRepository;
        this.jdRepository = jdRepository;
        this.resumeRepository = resumeRepository;
        this.taskRepository = taskRepository;
        this.materialSelector = materialSelector;
        this.injectionDetector = injectionDetector;
        this.promptBuilder = promptBuilder;
        this.schemaValidator = schemaValidator;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 校验生成请求中的资料 ID 归属。供 Controller 在创建任务前同步调用。
     * 跨用户或不存在的 included/preferred ID → NOT_FOUND。
     */
    public void validateMaterialIds(Long userId, List<Long> includedIds,
                                    List<Long> preferredIds, List<Long> excludedIds) {
        List<CareerMaterial> userMaterials = materialRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        Set<Long> ownedIds = new HashSet<>();
        for (CareerMaterial m : userMaterials) {
            ownedIds.add(m.getId());
        }
        if (includedIds != null) {
            for (Long id : includedIds) {
                if (!ownedIds.contains(id)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "资料不存在: " + id);
                }
            }
        }
        if (preferredIds != null) {
            for (Long id : preferredIds) {
                if (!ownedIds.contains(id)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "资料不存在: " + id);
                }
            }
        }
        // excluded: 不存在的 ID 静默忽略
    }

    /**
     * 执行岗位定制生成任务。由 TaskExecutionService 分发调用。
     *
     * @return result_json 内容(草稿 + 选中/未选/缺失 + 版本 + 警告)
     * @throws BusinessException 校验失败时抛出(AI_FAILURE)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeTask(AiTask task) {
        Long userId = task.getUserId();
        Map<String, Object> snapshot = task.getInputSnapshotJson();

        // 1. 解析输入
        Long jdId = toLong(snapshot.get("jobDescriptionId"));
        Long resumeId = toLong(snapshot.get("targetResumeId"));

        // 2. 校验简历归属（targetResumeId 可选：不传时确认后自动创建岗位简历）
        if (resumeId != null) {
            resumeRepository.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
        }

        // 3. 校验 JD 归属
        JobDescription jd = jdRepository.findByIdAndUserId(jdId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));

        // 4. Prompt Injection 检测
        PromptInjectionDetector.DetectionResult detection =
                injectionDetector.detect(jd.getJdText(), List.of());
        List<String> warnings = new ArrayList<>();
        if (detection.suspicious()) {
            warnings.add("PromptInjectionDetected");
            log.warn("Prompt injection detected for task {}: {}", task.getId(), detection.matchedPatterns());
        }

        // 5. 加载资料并选择
        List<CareerMaterial> allMaterials = materialRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        JobGenerationRequest genReq = parseRequest(snapshot);
        MaterialSelector.SelectionResult selection = materialSelector.select(userId, allMaterials, genReq);

        // 6. 空资料库处理
        boolean hasMaterials = !selection.fixed().isEmpty()
                || !selection.preferred().isEmpty()
                || !selection.normal().isEmpty();
        if (!hasMaterials) {
            warnings.add("InsufficientMaterials");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", "none");
            result.put("draftResumeJson", Map.of(
                    "basics", Map.of("_pending", Map.of("reason", "无资料"))));
            result.put("selected", List.of());
            result.put("unselected", List.of());
            result.put("missing", List.of(Map.of("section", "basics", "reason", "资料库为空")));
            result.put("warnings", warnings);
            result.put("promptVersion", promptVersion);
            result.put("schemaVersion", schemaVersion);
            return result;
        }

        // 7. 构建 Prompt
        JobGenerationPromptBuilder.Prompt prompt = promptBuilder.build(
                jd, selection.fixed(), selection.preferred(), selection.normal(), promptVersion);

        // 8. 调用 AI Provider(将构建好的 prompt 传入 context,供真实 Provider 使用)
        Map<String, Object> ctxInput = new HashMap<>(snapshot);
        ctxInput.put("_systemPrompt", prompt.system());
        ctxInput.put("_taskPrompt", prompt.task());
        ctxInput.put("_dataPrompt", prompt.data());
        AiCallContext ctx = new AiCallContext(AiTaskType.JOB_GENERATION, ctxInput, DEFAULT_TIMEOUT_MS);
        AiCallResult callResult = providerRegistry.route(AiTaskType.JOB_GENERATION).call(ctx);
        if (!callResult.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE,
                    "AI 调用失败: " + callResult.errorMessage());
        }

        // 9. Schema 校验
        Map<String, Object> data = callResult.data();
        Map<String, Object> draft = (Map<String, Object>) data.get("draftResumeJson");
        if (draft == null) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 输出缺少 draftResumeJson");
        }
        try {
            schemaValidator.validate(draft, schemaVersion);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "草稿 Schema 校验失败: " + e.getMessage());
        }

        // 10. 构建 selected/unselected/missing
        List<SelectedMaterialEntry> selected = buildSelected(selection);
        List<UnselectedMaterialEntry> unselected = buildUnselected(selection);
        List<MissingItem> missing = buildMissing(draft);

        // 11. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerRegistry.route(AiTaskType.JOB_GENERATION).code());
        result.put("draftResumeJson", draft);
        result.put("selected", selected);
        result.put("unselected", unselected);
        result.put("missing", missing);
        result.put("warnings", warnings);
        result.put("promptVersion", promptVersion);
        result.put("schemaVersion", schemaVersion);
        return result;
    }

    private JobGenerationRequest parseRequest(Map<String, Object> snapshot) {
        Long resumeId = toLong(snapshot.get("targetResumeId"));
        Long jdId = toLong(snapshot.get("jobDescriptionId"));
        Map<String, Object> input = snapshot.get("input") instanceof Map<?, ?> rawInput
                ? rawInput.entrySet().stream().collect(HashMap::new,
                        (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()),
                        HashMap::putAll)
                : snapshot;
        List<Long> included = toLongList(input.get("includedMaterialIds"));
        List<Long> preferred = toLongList(input.get("preferredMaterialIds"));
        List<Long> excluded = toLongList(input.get("excludedMaterialIds"));
        return new JobGenerationRequest(resumeId, jdId, included, preferred, excluded);
    }

    private List<SelectedMaterialEntry> buildSelected(MaterialSelector.SelectionResult selection) {
        List<SelectedMaterialEntry> selected = new ArrayList<>();
        Map<String, Integer> sectionCounters = new HashMap<>();
        for (CareerMaterial m : selection.fixed()) {
            selected.add(toSelectedEntry(m, sectionCounters, "USER_FIXED"));
        }
        for (CareerMaterial m : selection.preferred()) {
            selected.add(toSelectedEntry(m, sectionCounters, "PREFERRED"));
        }
        for (CareerMaterial m : selection.normal()) {
            selected.add(toSelectedEntry(m, sectionCounters, "AUTO_SELECTED"));
        }
        return selected;
    }

    private SelectedMaterialEntry toSelectedEntry(CareerMaterial m,
                                                   Map<String, Integer> sectionCounters,
                                                   String reason) {
        String section = sectionForType(m);
        int index = sectionCounters.getOrDefault(section, 0);
        sectionCounters.put(section, index + 1);
        return new SelectedMaterialEntry(m.getId(), section + "[" + index + "]", reason);
    }

    private List<UnselectedMaterialEntry> buildUnselected(MaterialSelector.SelectionResult selection) {
        List<UnselectedMaterialEntry> unselected = new ArrayList<>();
        selection.unselectedReasons().forEach((id, reason) ->
                unselected.add(new UnselectedMaterialEntry(id, reason, reason)));
        return unselected;
    }

    private List<MissingItem> buildMissing(Map<String, Object> draft) {
        List<MissingItem> missing = new ArrayList<>();
        Set<String> expectedSections = Set.of("basics", "work", "education", "skills");
        for (String section : expectedSections) {
            if (!draft.containsKey(section)) {
                missing.add(new MissingItem(section, "资料库缺少" + section + "相关内容"));
            }
        }
        return missing;
    }

    private String sectionForType(CareerMaterial m) {
        return switch (m.getMaterialType()) {
            case WORK_EXPERIENCE -> "work";
            case PROJECT_EXPERIENCE -> "projects";
            case SKILL -> "skills";
            case EDUCATION -> "education";
            case CERTIFICATE -> "certificates";
            case HIGHLIGHT, AWARD -> "basics";
        };
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof Number n ? n.longValue() : Long.parseLong(item.toString()))
                    .toList();
        }
        return List.of();
    }
}
