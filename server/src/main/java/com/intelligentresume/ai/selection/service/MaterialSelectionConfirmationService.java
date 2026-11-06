package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.generation.service.JobGenerationInputAssembler;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.task.domain.*;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.task.service.IdempotencyService;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 「选材确认」：把一次选材任务的推荐结果固化成 JOB_GENERATION 子任务的不可变快照。
 *
 * <p>新人三问：①业务问题：AI 推荐完资料后，用户要挑一批"就用这些"并点确认；
 * 本类负责把这次选择连同岗位/档案一起冻结成快照，创建真正的"生成简历"任务；
 * ②链路位置：被 {@code JobMaterialSelectionController#confirm} 调用；上游是选材任务
 * （JOB_MATERIAL_SELECTION），下游创建 JOB_GENERATION 子任务，快照交给
 * {@code JobGenerationInputAssembler} 变成 prompt；③改坏它用户会看到：重复点确认产生多个
 * 生成任务（配额被烧两次）、没配额却被标记"已确认"卡死、或确认了资料范围之外的资料
 * （越权把别人的/新加的资料带进生成）。
 *
 * <p>术语：幂等（同一请求发两次效果和一次一样——重试不会重复创建子任务）、
 * 乐观锁（提交时检查数据没被别人改过，改过就失败要求刷新）、
 * 悲观锁（读数据库时就加锁，别的请求必须等，彻底挡住并发确认）。
 *
 * <p>要点（顺序即红线，改任何一步之前先读完整段 {@link #confirm}）：
 * <ul>
 *   <li>幂等重放（同一 userId + Idempotency-Key 且 fingerprint 相同）在**任何写库和令牌校验之前**
 *       返回既有子任务；重试不能因为"这次没带预览令牌"而失败，也不能产生第二条子任务。</li>
 *   <li>令牌校验用服务端自己组装的 providerInput 重算，位置刻意在"幂等之后、配额与写库之前"：
 *       被拒时 ai_task 零新增。</li>
 *   <li>配额检查必须在创建子任务**和**把 selection 置为 CONFIRMED **之前**：否则配额不足会留下
 *       "选材已确认但永远生成不了"的死锁。</li>
 *   <li>并发保护靠 {@code findByIdForUpdate} 悲观锁 + {@code taskUpdatedAt} 乐观比对，
 *       两者缺一都会被并发确认穿透。</li>
 *   <li>{@link #MAX_FINAL_MATERIALS} 是硬编码 30，而 {@code MaterialSelector} 的上限来自配置
 *       {@code app.ai.generation.max-selected-materials}；改配置而不改这里会出现"选材放过、
 *       生成阶段裁剪"的不一致。</li>
 * </ul>
 */
@Service
public class MaterialSelectionConfirmationService {

    private static final int MAX_FINAL_MATERIALS = 30;
    private final AiTaskRepository taskRepository;
    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final PersonalProfileRepository profileRepository;
    private final IdempotencyService idempotencyService;
    private final AiConsentService consentService;
    private final AiQuotaService quotaService;
    private final JobGenerationInputAssembler inputAssembler;
    private final AiPreviewGuard previewGuard;

    public MaterialSelectionConfirmationService(AiTaskRepository taskRepository,
                                                CareerMaterialRepository materialRepository,
                                                JobDescriptionRepository jobRepository,
                                                PersonalProfileRepository profileRepository,
                                                IdempotencyService idempotencyService,
                                                AiConsentService consentService,
                                                AiQuotaService quotaService,
                                                JobGenerationInputAssembler inputAssembler,
                                                AiPreviewGuard previewGuard) {
        this.taskRepository = taskRepository;
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.idempotencyService = idempotencyService;
        this.consentService = consentService;
        this.quotaService = quotaService;
        this.inputAssembler = inputAssembler;
        this.previewGuard = previewGuard;
    }

    /**
     * 确认选材 → 创建 JOB_GENERATION 子任务（状态 PENDING），并返回该子任务。
     *
     * <p>校验链即方法体的执行顺序，每步的业务含义：
     * ①锁住并校验任务（存在/归属/类型，跨用户一律 404 不泄露存在性）；
     * ②查用户同意书（没授权把资料发给 AI 就到此为止）；
     * ③幂等重放：同 userId + 幂等键（Idempotency-Key 请求头，防止重复提交）且内容指纹相同
     *   → 直接返回既有子任务，重试不产生第二条；指纹不同 → 409（同一键提交了不同内容）；
     * ④任务状态与版本戳：选材必须已成功且未确认过，{@code taskUpdatedAt} 是乐观锁戳——
     *   与库里不一致说明页面停留期间数据变了，要求刷新后再确认；
     * ⑤组装快照并校验预览令牌（必须先看过预览并确认）；
     * ⑥配额检查（每日可用次数）；
     * ⑦落库：创建子任务 + 把 selection 置为 CONFIRMED。
     *
     * <p>任一步失败都保持 selection 的 confirmationStatus = PENDING——
     * 特别是 ⑥ 必须在 ⑦ 之前，否则配额不足会留下"选材已确认但永远生成不了"的死锁。
     *
     * @param idempotencyKey 为空时退化成本地确定性键 {@code selection-confirm:<selectionId>}，
     *                       因此**同一个 selection 不带 key 重复确认只会得到同一个子任务**
     * @return 已落库的 JOB_GENERATION 子任务（幂等重放时是既有那条）
     */
    @Transactional
    public AiTask confirm(Long taskId, ConfirmMaterialsRequest request, String idempotencyKey,
                          String previewToken, Long userId) {
        AiTask selection = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found"));
        if (!selection.getUserId().equals(userId) || selection.getTaskType() != AiTaskType.JOB_MATERIAL_SELECTION) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found");
        }
        if (!consentService.hasValidConsent(userId, AiTaskType.JOB_GENERATION.name(),
                List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"))) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        LinkedHashSet<Long> selectedIds = resolvedSelection(request);
        String childKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "selection-confirm:" + selection.getId() : idempotencyKey;
        Map<String, Object> confirmationInput = new LinkedHashMap<>();
        confirmationInput.put("selectionTaskId", selection.getId());
        confirmationInput.put("selectedMaterialIds", new ArrayList<>(selectedIds));
        confirmationInput.put("resumeTitle", Objects.toString(request.resumeTitle(), ""));
        String fingerprint = idempotencyService.fingerprint(confirmationInput);
        Optional<AiTask> existing = taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                userId, AiTaskType.JOB_GENERATION, childKey);
        if (existing.isPresent()) {
            if (existing.get().getRequestFingerprint().equals(fingerprint)) return existing.get();
            throw new BusinessException(ErrorCode.CONFLICT, "Selection was already confirmed with different materials");
        }
        if (selection.getStatus() != AiTaskStatus.SUCCESS
                || selection.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Selection task cannot be confirmed");
        }
        if (!Objects.equals(selection.getUpdatedAt(), request.taskUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Selection task changed; refresh before confirming");
        }

        Map<String, Object> childSnapshot = buildGenerationInput(selection, request, userId);

        // R32 AC-R32-9 / AC-R32-10：必须先看过 /confirm-materials/preview 的确切内容并确认。
        // 校验用服务端**自己组装**的 providerInput 重算令牌（客户端伪造不了"预览过"）。
        // 位置刻意为「幂等重放之后、任何写库之前」：重试不该因缺令牌失败，被拒时 ai_task 零新增。
        previewGuard.require(AiTaskType.JOB_GENERATION,
                inputAssembler.fromSnapshot(childSnapshot).providerInput(), previewToken);

        // 配额闸门（模块核实报告 P1-2）：确认选材会隐式创建 JOB_GENERATION 子任务，
        // 必须与 AiTaskService.create 走同一配额检查，否则"确认选材"路径会绕过每日配额限制。
        // 该检查必须在创建子任务之前、且在把 selection 置为 CONFIRMED 之前执行：
        // 配额不足时直接抛出 RATE_LIMITED，selection 的 confirmationStatus 保持 PENDING，
        // 用户不会陷入"选材已确认但无法生成"的死锁状态。
        // 与 AiTaskService.create 一致：幂等重放（上方 existing 命中提前返回）不重复消耗配额。
        quotaService.check(userId, AiTaskType.JOB_GENERATION);

        AiTask child = new AiTask();
        child.setUserId(userId);
        child.setParentTaskId(selection.getId());
        child.setTaskType(AiTaskType.JOB_GENERATION);
        child.setIdempotencyKey(childKey);
        child.setRequestFingerprint(fingerprint);
        child.setInputSnapshotJson(childSnapshot);
        child.setStatus(AiTaskStatus.PENDING);
        child.setRetryCount(0);
        child = taskRepository.save(child);
        selection.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        taskRepository.save(selection);
        return child;
    }

    /**
     * 组装 JOB_GENERATION 子任务的不可变输入快照 —— confirm 与发送前预览**共用同一实现**
     * （R32 AC-R32-8）。只读：不写库、不调 provider。
     *
     * <p>业务上这步是"把用户的最终选择打包"：岗位快照 + 用户选中的资料快照 +
     * 个人档案快照 + JD 未满足的要求，全部冻成一份 JSON——worker 稍后执行时无论
     * 用户改了什么，任务都按确认那一刻的内容跑。
     *
     * <p>预览时 {@code confirmationStatus} 仍是 PENDING 属正常，故本方法不校验确认状态；
     * 它校验快照能成立的前提：岗位/素材归属、素材来自本轮候选、1..30 条。
     * **任务归属与状态由调用方先校验**（{@code confirm} 在方法开头，预览走
     * {@code JobGenerationPreviewService}）。
     *
     * @throws BusinessException 归属不符 → {@code 40401}；素材越界或数量非法 → {@code 40001}
     */
    public Map<String, Object> buildGenerationInput(AiTask selection, ConfirmMaterialsRequest request, Long userId) {
        LinkedHashSet<Long> selectedIds = resolvedSelection(request);
        LinkedHashSet<Long> requestedIds = requestedIds(request);
        LinkedHashSet<Long> forcedIds = forcedIds(request);
        Long jobId = longValue(selection.getInputSnapshotJson().get("jobDescriptionId"));
        JobDescription job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        Map<Long, CareerMaterial> owned = materialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId).stream()
                .collect(Collectors.toMap(CareerMaterial::getId, material -> material));
        if (!owned.keySet().containsAll(selectedIds)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Career material not found");
        }
        validateSelectionBoundary(selection, requestedIds, forcedIds);

        Map<String, Object> childSnapshot = new LinkedHashMap<>();
        childSnapshot.put("taskType", AiTaskType.JOB_GENERATION.name());
        childSnapshot.put("jobDescriptionId", jobId);
        childSnapshot.put("jobSnapshot", MaterialSelectionSnapshotMapper.of(job));
        childSnapshot.put("materialSnapshots", selectedIds.stream()
                .map(id -> MaterialSelectionSnapshotMapper.of(owned.get(id))).toList());
        Map<String, Object> selectionResult = selection.getResultJson() == null ? Map.of() : selection.getResultJson();
        childSnapshot.put("missingRequirements", stringList(selectionResult.get("missingRequirements")));
        childSnapshot.put("personalProfileSnapshot", profileRepository.findByUserId(userId)
                .map(MaterialSelectionSnapshotMapper::of).orElseGet(LinkedHashMap::new));
        String resumeTitle = request.resumeTitle();
        if (resumeTitle == null || resumeTitle.isBlank()) {
            Object originalTitle = selection.getInputSnapshotJson().get("resumeTitle");
            resumeTitle = originalTitle == null ? null : originalTitle.toString();
        }
        if (resumeTitle != null && !resumeTitle.isBlank()) childSnapshot.put("resumeTitle", resumeTitle);
        return childSnapshot;
    }

    private LinkedHashSet<Long> requestedIds(ConfirmMaterialsRequest request) {
        return new LinkedHashSet<>(request.selectedMaterialIds());
    }

    private LinkedHashSet<Long> forcedIds(ConfirmMaterialsRequest request) {
        return request.forcedIncludedMaterialIds() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(request.forcedIncludedMaterialIds());
    }

    /** 常规勾选 ∪ 强制加入，1..30 条。 */
    private LinkedHashSet<Long> resolvedSelection(ConfirmMaterialsRequest request) {
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(requestedIds(request));
        selectedIds.addAll(forcedIds(request));
        if (selectedIds.isEmpty() || selectedIds.size() > MAX_FINAL_MATERIALS) {
            throw new BusinessException(ErrorCode.VALIDATION, "Select between 1 and 30 materials");
        }
        return selectedIds;
    }

    /**
     * 两道边界：常规勾选必须来自本轮推荐/未选候选（防越权引用别人的资料），
     * 强制加入只允许"全局排除"的资料（用户主动排除的不许反向强制，否则排除操作形同虚设）。
     *
     * <p>候选集来自选材任务的 {@code result_json}（recommended ∪ unselected），
     * 不是实时查库 —— 所以推荐结果产生之后新建的资料无法被确认。
     */
    private void validateSelectionBoundary(AiTask selection, Set<Long> requestedIds, Set<Long> forcedIds) {
        Map<String, Object> result = selection.getResultJson() == null ? Map.of() : selection.getResultJson();
        Set<Long> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(materialIds(result.get("recommended")));
        candidateIds.addAll(materialIds(result.get("unselected")));

        Set<Long> manuallyExcluded = new LinkedHashSet<>();
        Object rawInput = selection.getInputSnapshotJson().get("input");
        if (rawInput instanceof Map<?, ?> input) {
            manuallyExcluded.addAll(longSet(input.get("excludedMaterialIds")));
        }
        Set<Long> forceableIds = new LinkedHashSet<>();
        if (result.get("excluded") instanceof Collection<?> excluded) {
            for (Object item : excluded) {
                if (!(item instanceof Map<?, ?> value)) continue;
                Long id = longValue(value.get("materialId"));
                if (id == null || manuallyExcluded.contains(id)) continue;
                if ("GLOBAL".equals(value.get("exclusionReason"))
                        || UsagePreference.EXCLUDED.name().equals(value.get("usagePreference"))) {
                    forceableIds.add(id);
                }
            }
        }

        Set<Long> regularIds = new LinkedHashSet<>(requestedIds);
        regularIds.removeAll(forcedIds);
        if (!candidateIds.containsAll(regularIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Selected materials must come from the selection candidates");
        }
        if (!forceableIds.containsAll(forcedIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Only globally excluded materials can be forcibly included");
        }
    }

    private Set<Long> materialIds(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return Set.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> value) {
                Long id = longValue(value.get("materialId"));
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    private Set<Long> longSet(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return Set.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object value : collection) {
            Long id = longValue(value);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return List.of();
        return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
