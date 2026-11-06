package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.JobGenerationRequest;
import com.intelligentresume.ai.generation.dto.MissingItem;
import com.intelligentresume.ai.generation.dto.SelectedMaterialEntry;
import com.intelligentresume.ai.provider.*;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.domain.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.careermaterial.service.CareerMaterialEvidence;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 岗位简历生成：把「已确认的选材快照」变成一份可审核的简历草稿。
 *
 * <h2>新人三问</h2>
 * <ol>
 *   <li><b>解决什么业务问题</b>：让 AI 替用户把「确认过的那批资料」改写成贴合某个岗位的简历草稿，
 *       并且草稿里每句话都能追溯到来源资料（溯源 = 每条内容标注它出自用户哪条素材），
 *       用户不想要"凭空编出来"的内容；</li>
 *   <li><b>在链路哪里</b>：用户确认选材（{@code MaterialSelectionConfirmationService#confirm}）后，
 *       后台 worker 领到 JOB_GENERATION 任务就调进本类的 {@code executeTask}；本类调
 *       {@code JobGenerationInputAssembler}（组装 prompt）、{@code AiProviderRegistry}（真正调模型）、
 *       {@code JobGenerationSchemaValidator}（校验产出）；产出的 {@code draftResumeJson} 写进任务结果，
 *       最终由确认页 + {@code DraftCommitService} 落成简历版本；</li>
 *   <li><b>改坏它用户会看到什么</b>：生成整体失败（AI_FAILURE）、草稿结构非法全被拒、
 *       联系方式被模型编造冒充用户本人、或"资料明明被用了却显示无人引用"
 *       （selected 记录错了会让资料留存清扫误判，见 {@code collectSources}）。</li>
 * </ol>
 *
 * <h2>两条执行路径，新任务只准走第一条</h2>
 * <ul>
 *   <li><b>快照路径</b>（快照里有 {@code jobSnapshot}）：用不可变快照重建 JD、资料与 prompt，
 *       与「发送前预览」共用 {@code JobGenerationInputAssembler} —— 预览看到的就是实际发出的；</li>
 *   <li><b>旧任务路径</b>（没有 {@code jobSnapshot}）：只为升级时仍在队列里的任务保留，
 *       会重新查库取资料，<b>不具备快照语义</b>。不要给它加新能力，也不要让新任务落到这里。</li>
 * </ul>
 *
 * <h2>后处理顺序不能换</h2>
 * <pre>normalizeAliases → schemaValidator → mergePersonalProfile → collectSources</pre>
 * <ol>
 *   <li><b>先归一别名</b>：模型常用 {@code title} / {@code institution} 这类同义字段，
 *       不归一会直接被 schema 判成非法，整个任务白跑；</li>
 *   <li><b>再校验 schema</b>：校验同时确认「引用的 materialId 必须在本次候选里」，挡住幻觉引用；</li>
 *   <li><b>最后合并联系方式</b>：把模型可能编造或回显的 name/email/phone/location/website 整段删掉，
 *       换成服务端档案里的真值 —— 这是「联系方式不进模型」的收口点，顺序提前会让 schema 先拒掉它们；</li>
 *   <li>{@code collectSources} 只记录<b>真实存在</b>的引用（用 {@code byId} 过滤），
 *       幻觉 ID 不会进 {@code selected} —— 否则留存清扫会误判"这条资料还有人用"，永远删不掉。</li>
 * </ol>
 *
 * <h2>注入检测只告警，不阻断</h2>
 * 命中可疑模式时只写 {@code PromptInjectionDetected} 警告并记日志，<b>任务继续执行</b>：
 * 误判的代价（用户拿不到简历）高于收益；真正的防线是 system prompt + 输出 schema 校验。
 */
@Service
public class JobGenerationService {

    private static final Logger log = LoggerFactory.getLogger(JobGenerationService.class);
    private static final Pattern LEGACY_SOURCE_ID = Pattern.compile("materialId\\s*=\\s*(\\d+)");
    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final MaterialSelector materialSelector;
    private final PromptInjectionDetector injectionDetector;
    private final JobGenerationPromptBuilder promptBuilder;
    private final JobGenerationInputAssembler inputAssembler;
    private final JobGenerationSchemaValidator schemaValidator;
    private final AiProviderRegistry providerRegistry;

    @Value("${app.ai.generation.prompt-version:v1.0.0}")
    private String promptVersion;
    @Value("${app.ai.generation.schema-version:v1.0.0}")
    private String schemaVersion;

    // Keep the established constructor shape so existing tests and wiring remain compatible.
    public JobGenerationService(CareerMaterialRepository materialRepository,
                                JobDescriptionRepository jobRepository,
                                ResumeRepository resumeRepository,
                                AiTaskRepository ignoredTaskRepository,
                                MaterialSelector materialSelector,
                                PromptInjectionDetector injectionDetector,
                                JobGenerationPromptBuilder promptBuilder,
                                JobGenerationInputAssembler inputAssembler,
                                JobGenerationSchemaValidator schemaValidator,
                                AiProviderRegistry providerRegistry) {
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.materialSelector = materialSelector;
        this.injectionDetector = injectionDetector;
        this.promptBuilder = promptBuilder;
        this.inputAssembler = inputAssembler;
        this.schemaValidator = schemaValidator;
        this.providerRegistry = providerRegistry;
    }

    public void validateMaterialIds(Long userId, List<Long> includedIds,
                                    List<Long> preferredIds, List<Long> excludedIds) {
        List<CareerMaterial> ownedMaterials = materialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
        Set<Long> ownedIds = ownedMaterials.stream().map(CareerMaterial::getId).collect(Collectors.toSet());
        validateOwned(includedIds, ownedIds);
        validateOwned(preferredIds, ownedIds);
        Set<Long> unusableIds = ownedMaterials.stream()
                .filter(material -> !CareerMaterialEvidence.isReady(material))
                .map(CareerMaterial::getId)
                .collect(Collectors.toSet());
        if (containsAny(includedIds, unusableIds) || containsAny(preferredIds, unusableIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "选中的资料需要包含来源原文或有意义的结构化内容");
        }
    }

    private boolean containsAny(List<Long> ids, Set<Long> candidates) {
        return ids != null && ids.stream().anyMatch(candidates::contains);
    }

    private void validateOwned(List<Long> ids, Set<Long> ownedIds) {
        if (ids != null && !ownedIds.containsAll(ids)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Career material not found");
        }
    }

    /**
     * 生成一条 JOB_GENERATION 任务的主流程，共七步：
     * <ol>
     *   <li>① 读任务快照（快照 = 用户确认那一刻冻结下来的岗位/资料/档案 JSON，之后不再查库改主意）；
     *       没有 {@code jobSnapshot} 的是升级前的旧任务，走 {@link #executeLegacyTask} 兼容路径；</li>
     *   <li>② 目标简历必须存在且属于任务本人，防止把草稿写到别人的简历上；</li>
     *   <li>③ 与「发送前预览」共用 {@link JobGenerationInputAssembler} 重建 JD/资料/档案并拼出
     *       三段式 prompt —— 预览看到的就是这里实际发出的（共用实现保证，不是口头约定）；</li>
     *   <li>④ 注入检测：只扫 JD 正文与资料原文（用户自己写的"忽略以上指令"可能是正常业务词汇），
     *       命中只加警告不阻断，见类注释；</li>
     *   <li>⑤ 调模型拿草稿：失败直接抛 AI_FAILURE，任务由 worker 的重试机制兜底；</li>
     *   <li>⑥ 后处理四连，顺序不能换（见类注释）：normalizeAliases → schemaValidator →
     *       mergePersonalProfile → collectSources；</li>
     *   <li>⑦ 打包结果：草稿、真实被引用的资料、缺失板块、质量摘要等，写进任务结果给确认页。</li>
     * </ol>
     * 为什么 ②③④ 必须在 ⑤ 之前：任何前置不成立都不该烧一次模型调用（费钱且慢）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeTask(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (!snapshot.containsKey("jobSnapshot")) {
            return executeLegacyTask(task, snapshot);
        }
        Long resumeId = toLong(snapshot.get("targetResumeId"));
        if (resumeId != null) {
            resumeRepository.findByIdAndUserId(resumeId, task.getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume not found"));
        }
        // 组装（重建实体 + prompt）与发送前预览同源，见 JobGenerationInputAssembler（R32 AC-R32-8）
        JobGenerationInputAssembler.GenerationInput input = inputAssembler.fromSnapshot(snapshot);
        JobDescription job = input.job();
        List<CareerMaterial> materials = input.materials();
        Map<String, Object> profile = input.profile();

        List<String> sourceTexts = materials.stream().map(CareerMaterial::getSourceText)
                .filter(Objects::nonNull).toList();
        // 只扫 JD 正文与资料原文：用户自己写的"忽略以上指令"可能是正常业务词汇，
        // 所以这里只产生警告，不据此拒绝任务（见类注释）。
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(job.getJdText(), sourceTexts);
        List<String> warnings = new ArrayList<>();
        if (detection.suspicious()) {
            warnings.add("PromptInjectionDetected");
            log.warn("Prompt injection detected for task {}: {}", task.getId(), detection.matchedPatterns());
        }

        AiCallResult response = providerRegistry.route(AiTaskType.JOB_GENERATION)
                .call(new AiCallContext(AiTaskType.JOB_GENERATION, input.providerInput()));
        if (!response.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Resume generation failed: " + response.errorMessage());
        }
        Object rawDraft = response.data().get("draftResumeJson");
        if (!(rawDraft instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI output has no draftResumeJson");
        }
        Map<String, Object> draft = (Map<String, Object>) rawMap;
        normalizeAliases(draft);
        try {
            schemaValidator.validate(draft, schemaVersion,
                    materials.stream().map(CareerMaterial::getId).collect(java.util.stream.Collectors.toSet()));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Draft schema validation failed: " + e.getMessage());
        }
        // 联系方式只在这里落地：删掉模型给的那一份，换成服务端档案的真值。
        // 顺序必须在 schema 校验之后 —— 提前会因 basics 多了字段而校验失败。
        mergePersonalProfile(draft, profile);

        Map<String, CareerMaterial> byId = new HashMap<>();
        materials.forEach(material -> byId.put(String.valueOf(material.getId()), material));
        List<SelectedMaterialEntry> selected = new ArrayList<>();
        collectSources(draft, "", byId, selected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerRegistry.route(AiTaskType.JOB_GENERATION).code());
        result.put("draftResumeJson", draft);
        result.put("selected", selected.stream().distinct().toList());
        result.put("unselected", List.of());
        List<MissingItem> missing = buildMissing(draft);
        result.put("missing", missing);
        result.put("qualitySummary", buildQualitySummary(draft, missing,
                stringList(snapshot.get("missingRequirements")), hasTrustedProfile(profile)));
        result.put("warnings", warnings);
        result.put("promptVersion", promptVersion);
        result.put("schemaVersion", schemaVersion);
        return result;
    }

    /**
     * Older queued tasks predate the material-selection snapshot. Keep them
     * executable during the migration, while every new task must use the
     * immutable snapshot path above.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeLegacyTask(AiTask task, Map<String, Object> snapshot) {
        Long resumeId = toLong(snapshot.get("targetResumeId"));
        if (resumeId != null) {
            resumeRepository.findByIdAndUserId(resumeId, task.getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume not found"));
        }
        Long jobId = toLong(snapshot.get("jobDescriptionId"));
        JobDescription job = jobRepository.findByIdAndUserId(jobId, task.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        Map<String, Object> input = snapshot.get("input") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : snapshot;
        JobGenerationRequest request = new JobGenerationRequest(resumeId, jobId,
                longList(input.get("includedMaterialIds")), longList(input.get("preferredMaterialIds")),
                longList(input.get("excludedMaterialIds")));
        List<CareerMaterial> allMaterials = materialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(task.getUserId());
        MaterialSelector.SelectionResult selection = materialSelector.select(task.getUserId(), allMaterials, request);
        List<CareerMaterial> materials = new ArrayList<>();
        materials.addAll(selection.fixed());
        materials.addAll(selection.preferred());
        materials.addAll(selection.normal());
        if (materials.isEmpty()) {
            Map<String, Object> emptyDraft = Map.of();
            List<Map<String, Object>> missing = List.of(
                    Map.of("section", "basics", "reason", "No eligible career materials"));
            return Map.of("draftResumeJson", emptyDraft, "selected", List.of(), "unselected", List.of(),
                    "missing", missing, "qualitySummary", buildQualitySummary(emptyDraft, missing, List.of(), false),
                    "warnings", List.of("InsufficientMaterials"), "promptVersion", promptVersion,
                    "schemaVersion", schemaVersion);
        }
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(job.getJdText(),
                materials.stream().map(CareerMaterial::getSourceText).filter(Objects::nonNull).toList());
        List<String> warnings = detection.suspicious() ? List.of("PromptInjectionDetected") : List.of();
        JobGenerationPromptBuilder.Prompt prompt = promptBuilder.build(job, selection.fixed(), selection.preferred(),
                selection.normal(), promptVersion);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("_systemPrompt", prompt.system());
        providerInput.put("_taskPrompt", prompt.task());
        providerInput.put("_dataPrompt", prompt.data());
        AiCallResult response = providerRegistry.route(AiTaskType.JOB_GENERATION)
                .call(new AiCallContext(AiTaskType.JOB_GENERATION, providerInput));
        if (!response.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Resume generation failed: " + response.errorMessage());
        }
        Object rawDraft = response.data().get("draftResumeJson");
        if (!(rawDraft instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI output has no draftResumeJson");
        }
        Map<String, Object> draft = (Map<String, Object>) rawMap;
        normalizeAliases(draft);
        try {
            schemaValidator.validate(draft, schemaVersion,
                    materials.stream().map(CareerMaterial::getId).collect(java.util.stream.Collectors.toSet()));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Draft schema validation failed: " + e.getMessage());
        }
        Map<String, CareerMaterial> byId = new HashMap<>();
        materials.forEach(material -> byId.put(String.valueOf(material.getId()), material));
        List<SelectedMaterialEntry> selected = new ArrayList<>();
        collectSources(draft, "", byId, selected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerRegistry.route(AiTaskType.JOB_GENERATION).code());
        result.put("draftResumeJson", draft);
        result.put("selected", selected.stream().distinct().toList());
        result.put("unselected", selection.excluded());
        List<MissingItem> missing = buildMissing(draft);
        result.put("missing", missing);
        result.put("qualitySummary", buildQualitySummary(draft, missing, List.of(), false));
        result.put("warnings", warnings);
        result.put("promptVersion", promptVersion);
        result.put("schemaVersion", schemaVersion);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void normalizeAliases(Map<String, Object> draft) {
        renameEach(draft.get("work"), Map.of("title", "position", "role", "position"));
        renameEach(draft.get("projects"), Map.of("title", "name", "position", "role"));
        renameEach(draft.get("education"), Map.of("institution", "school", "field", "major", "studyType", "degree"));
        renameEach(draft.get("certificates"), Map.of("title", "name", "organization", "issuer"));
        renameEach(draft.get("skills"), Map.of("keywords", "items"));
        promoteCustomSectionSources(draft.get("customSections"));
    }

    @SuppressWarnings("unchecked")
    private void promoteCustomSectionSources(Object value) {
        if (!(value instanceof List<?> sections)) return;
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> rawSection)) continue;
            Map<String, Object> section = (Map<String, Object>) rawSection;
            if (section.containsKey("_source") || section.containsKey("_sources")
                    || section.containsKey("_pending")) continue;
            if (!(section.get("entries") instanceof List<?> entries)) continue;

            Map<String, Map<String, Object>> uniqueSources = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> rawEntry)) continue;
                Object sources = rawEntry.get("_sources");
                if (sources instanceof List<?> list) {
                    for (Object source : list) addPromotedSource(source, uniqueSources);
                }
                addPromotedSource(rawEntry.get("_source"), uniqueSources);
            }
            if (!uniqueSources.isEmpty()) {
                section.put("_sources", new ArrayList<>(uniqueSources.values()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addPromotedSource(Object value, Map<String, Map<String, Object>> sources) {
        if (!(value instanceof Map<?, ?> rawSource)) return;
        Map<String, Object> source = (Map<String, Object>) rawSource;
        Object materialId = source.get("materialId");
        if (materialId == null) return;
        String key = materialId + "\u0000" + Objects.toString(source.get("materialType"), "");
        sources.putIfAbsent(key, new LinkedHashMap<>(source));
    }

    @SuppressWarnings("unchecked")
    private void renameEach(Object value, Map<String, String> aliases) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = (Map<String, Object>) raw;
            aliases.forEach((from, to) -> {
                if (!map.containsKey(to) && map.containsKey(from)) map.put(to, map.remove(from));
            });
            Object highlights = map.get("highlights");
            if (highlights instanceof String text) map.put("highlights", List.of(text));
        }
    }

    /**
     * 把服务端档案里的联系方式写进草稿的 {@code basics}。
     *
     * <p>实现上先<b>无条件删除</b>模型输出的 name/email/phone/location/website，再按档案补回 ——
     * 不是"有就覆盖"，而是"先清空再写"。原因：模型可能把 JD 里的招聘方联系方式、
     * 或自己编的邮箱写进 basics，这类内容必须整段丢弃，不能因为"档案里没填"就留下。
     *
     * <p>档案值缺失时对应键直接不存在（{@code putKnown} 跳过空白），前端因此能区分
     * "用户没填"与"填了空字符串"。
     */
    @SuppressWarnings("unchecked")
    private void mergePersonalProfile(Map<String, Object> draft, Map<String, Object> profile) {
        Map<String, Object> basics = draft.get("basics") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : new LinkedHashMap<>();
        for (String key : List.of("name", "email", "phone", "location", "website")) {
            basics.remove(key);
        }
        putKnown(basics, "name", profile.get("fullName"));
        putKnown(basics, "email", profile.get("email"));
        putKnown(basics, "phone", profile.get("phone"));
        putKnown(basics, "location", profile.get("location"));
        putKnown(basics, "website", profile.get("website"));
        if (!basics.isEmpty()) basics.remove("_pending");
        draft.put("basics", basics);
    }

    private void putKnown(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) target.put(key, value);
    }

    private List<MissingItem> buildMissing(Map<String, Object> draft) {
        List<MissingItem> missing = new ArrayList<>();
        for (String section : List.of("basics", "work", "education", "skills")) {
            Object value = draft.get(section);
            if (isEmptySection(value)) {
                missing.add(new MissingItem(section, "No supported content"));
            } else {
                collectPending(value, section, missing);
            }
        }
        return missing;
    }

    private boolean isEmptySection(Object value) {
        return value == null
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    /**
     * Produces a small, user-facing review summary. It intentionally measures
     * only top-level resume entries because those are the items users can
     * accept, edit, or reject on the confirmation page.
     *
     * <p>readiness 三档：有条目缺来源或没有来源（pending/unsupported &gt; 0）→ {@code REQUIRES_ACTION}；
     * 否则若仍有缺口或未覆盖要求 → {@code REVIEW_RECOMMENDED}；两者都没有才是 {@code READY}。
     * 把 {@code REQUIRES_ACTION} 放宽成提示，等于让"没有出处的编造内容"直接进确认页。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildQualitySummary(Map<String, Object> draft, Collection<?> draftGaps,
                                                    Collection<?> missingRequirements, boolean personalProfileAvailable) {
        int total = 0;
        int sourced = 0;
        int pending = 0;
        int unsupported = 0;
        for (String section : JobGenerationSchemaValidator.SUPPORTED_SECTIONS) {
            Object value = draft.get(section);
            List<Map<String, Object>> entries = new ArrayList<>();
            if (value instanceof Map<?, ?> raw) {
                entries.add((Map<String, Object>) raw);
            } else if (value instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> raw) entries.add((Map<String, Object>) raw);
                }
            }
            for (Map<String, Object> entry : entries) {
                total++;
                boolean hasSource = hasSource(section, entry, personalProfileAvailable);
                boolean hasPending = containsPending(entry);
                if (hasSource) sourced++;
                if (hasPending) pending++;
                if (!hasSource && !hasPending) unsupported++;
            }
        }
        String readiness = pending > 0 || unsupported > 0 ? "REQUIRES_ACTION"
                : !draftGaps.isEmpty() || !missingRequirements.isEmpty() ? "REVIEW_RECOMMENDED" : "READY";
        return Map.of(
                "totalDraftItems", total,
                "sourcedItems", sourced,
                "pendingItems", pending,
                "unsupportedItems", unsupported,
                "draftGapCount", draftGaps.size(),
                "missingRequirementCount", missingRequirements.size(),
                "readiness", readiness);
    }

    private boolean hasSource(String section, Map<String, Object> entry, boolean personalProfileAvailable) {
        return entry.containsKey("_source") || entry.containsKey("_sources")
                || "basics".equals(section) && personalProfileAvailable;
    }

    @SuppressWarnings("unchecked")
    private boolean containsPending(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey("_pending")) return true;
            return map.entrySet().stream()
                    .filter(entry -> !entry.getKey().startsWith("_"))
                    .anyMatch(entry -> containsPending(entry.getValue()));
        }
        if (value instanceof List<?> list) return list.stream().anyMatch(this::containsPending);
        return false;
    }

    @SuppressWarnings("unchecked")
    private void collectPending(Object value, String path, List<MissingItem> missing) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey("_pending")) {
                Object pending = map.get("_pending");
                String reason = pending instanceof Map<?, ?> detail
                        ? Objects.toString(detail.get("reason"), "Missing information")
                        : Objects.toString(pending, "Missing information");
                missing.add(new MissingItem(path, reason));
            }
            map.forEach((key, child) -> {
                if (!key.startsWith("_")) collectPending(child, path + "." + key, missing);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectPending(list.get(i), path + "[" + i + "]", missing);
        }
    }

    /**
     * 递归收集草稿里**真实存在**的资料来源引用。
     *
     * <p>只认 {@code byId} 里有的 materialId：模型常给出不存在或已删除的 ID，
     * 原样记进 {@code selected} 会让留存清扫认为"这条资料还被引用"，从而永远不进入 A 档清理。
     * 同时兼容旧任务的 {@code _source} 字符串形态（{@code materialId=123}）。
     */
    @SuppressWarnings("unchecked")
    private void collectSources(Object value, String path, Map<String, CareerMaterial> byId,
                                List<SelectedMaterialEntry> selected) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            Object sources = map.get("_sources");
            if (sources instanceof List<?> list) {
                for (Object source : list) {
                    if (source instanceof Map<?, ?> detail) addSource(detail.get("materialId"), path, byId, selected);
                }
            }
            Object legacy = map.get("_source");
            if (legacy != null) {
                Matcher matcher = LEGACY_SOURCE_ID.matcher(legacy.toString());
                while (matcher.find()) addSource(matcher.group(1), path, byId, selected);
            }
            map.forEach((key, child) -> {
                if (!key.startsWith("_")) collectSources(child, path.isBlank() ? key : path + "." + key, byId, selected);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectSources(list.get(i), path + "[" + i + "]", byId, selected);
        }
    }

    private void addSource(Object rawId, String path, Map<String, CareerMaterial> byId,
                           List<SelectedMaterialEntry> selected) {
        CareerMaterial material = byId.get(String.valueOf(rawId));
        if (material != null) selected.add(new SelectedMaterialEntry(material.getId(), path, "AI_REFERENCED"));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(String::trim).filter(text -> !text.isEmpty()).toList();
    }

    private boolean hasTrustedProfile(Map<String, Object> profile) {
        return List.of("fullName", "email", "phone", "location", "website").stream()
                .map(profile::get)
                .anyMatch(value -> value != null && !value.toString().isBlank());
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().map(this::toLong).filter(Objects::nonNull).toList();
    }
}
