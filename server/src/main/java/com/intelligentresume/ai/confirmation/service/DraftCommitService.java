package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.domain.ResumeMaterialReference;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem.Decision;
import com.intelligentresume.ai.confirmation.repository.ResumeMaterialReferenceRepository;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.generation.service.SourcePathResolver;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.domain.SelectionReason;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.service.ResumeVersionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 草稿提交服务。在同一事务中完成：
 * <ol>
 *   <li>SELECT FOR UPDATE 锁定 ai_task</li>
 *   <li>校验状态、归属、乐观锁</li>
 *   <li>标准化草稿 JSON（剥离 _source / _pending）</li>
 *   <li>创建岗位简历（targetResumeId 为空时自动创建）</li>
 *   <li>创建 resume_version</li>
 *   <li>写入 resume_material_reference（含 source_snapshot_json）</li>
 *   <li>更新 ai_task 为 CONFIRMED</li>
 * </ol>
 *
 * <p>新人三问：①业务问题：用户在确认页对 AI 草稿逐条"接受/编辑/拒绝"后点提交，
 * 这里把人工确认结果一次性变成正式的简历版本——AI 永远不能直接写简历，必须经此关；
 * ②链路位置：被 {@code ConfirmationService#confirm} 调用（编排在外、事务在里）；
 * 上游是确认页请求，下游写 resume_version（正式简历版本）和 resume_material_reference（溯源表）；
 * ③改坏它用户会看到：确认失败/重复确认产生两个版本、别人内容混进自己的简历、
 * 编辑的内容丢失、或溯源记录缺失导致"这段话哪来的"查不出来。
 *
 * <p>术语：溯源（provenance）= 记录简历里每段内容来自哪条原始资料，便于审计与"资料还在被谁用"；
 * 乐观锁（taskUpdatedAt 比对，提交时数据被改过就拒绝）。
 *
 * <p>要点 / 红线:
 * <ul>
 *   <li>校验必须全部发生在**任何写库之前**(尤其 6b 的溯源准入):一旦提前写了 resume_version
 *       再失败,就只能靠事务回滚兜底,而回滚会掩盖真正的问题。</li>
 *   <li>{@code ai_task} 行先被 {@code SELECT FOR UPDATE} 锁住,同一任务的并发确认会被串行化。</li>
 *   <li>EDIT 决策会为每条被编辑的条目**新建**一条 career_material(标题固定为"用户确认编辑"),
 *       所以一次确认可能产生多条资料,返回的 {@code newMaterialIds} 就是它们。</li>
 *   <li>只有 ACCEPT 的选材才写 {@code resume_material_reference};EDIT 引用的是新建的用户资料
 *       (reason 固定 USER_EDITED),不能继续引用 AI 生成时的原始素材。</li>
 *   <li>{@code targetResumeIdOverride} 非空时要求该简历的 jobDescriptionId 与本次任务一致,
 *       否则 VALIDATION —— 防止把 A 岗位的生成结果覆盖到 B 岗位简历上。</li>
 * </ul>
 */
@Service
public class DraftCommitService {

    private final AiTaskRepository taskRepository;
    private final ResumeVersionService versionService;
    private final ResumeJsonNormalizer normalizer;
    private final CareerMaterialRepository materialRepository;
    private final ResumeMaterialReferenceRepository referenceRepository;
    private final SourcePathResolver pathResolver;
    private final ResumeRepository resumeRepository;

    public DraftCommitService(AiTaskRepository taskRepository,
                              ResumeVersionService versionService,
                              ResumeJsonNormalizer normalizer,
                              CareerMaterialRepository materialRepository,
                              ResumeMaterialReferenceRepository referenceRepository,
                              SourcePathResolver pathResolver,
                              ResumeRepository resumeRepository) {
        this.taskRepository = taskRepository;
        this.versionService = versionService;
        this.normalizer = normalizer;
        this.materialRepository = materialRepository;
        this.referenceRepository = referenceRepository;
        this.pathResolver = pathResolver;
        this.resumeRepository = resumeRepository;
    }

    /**
     * 事务性提交确认结果。方法内的编号注释就是执行顺序，整体分四个阶段：
     *
     * <p>阶段一（步骤 1-6b）：全部只读校验——锁任务 → 归属 → 状态 → 乐观锁 → 草稿存在
     * → 路径有效 → 溯源准入。**任何一条失败都不写库**，这样报错就是真实原因，
     * 不需要靠回滚"擦屁股"。
     *
     * <p>阶段二（步骤 7-8）：整理内容——剥掉草稿里的 AI 标记、应用用户决策（接受/编辑/拒绝），
     * EDIT 的条目另存为一条新的用户资料（用户的修改本身也是职业素材，可被下次生成复用）。
     *
     * <p>阶段三（步骤 9-10）：正式落库——创建/定位目标简历 → 写简历版本 → 写溯源引用
     * （快照方式：即使资料之后被删，"当时长什么样"仍可查）。
     *
     * <p>阶段四（步骤 11-12）：收尾——任务置 CONFIRMED、算出被拒路径返回给前端。
     *
     * <p>为什么顺序不能换：溯源准入（6b）必须在写版本（阶段三）之前——否则用户决策
     * 没覆盖全部 AI 引用这一错误要靠事务回滚暴露；锁（1）必须在所有校验之前——
     * 防止两个并发请求同时通过校验再各自写一份。
     *
     * @param resumeTitleOverride 用户指定的简历标题（可选，覆盖默认命名）
     * @param targetResumeIdOverride 用户选择更新已有简历时传入（可选）
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public CommitResult commit(Long taskId, List<ConfirmedDraftItem> items,
                               Map<String, Object> additionalResumeJson,
                               LocalDateTime taskUpdatedAt, Long userId,
                               String resumeTitleOverride, Long targetResumeIdOverride) {
        // 1. SELECT FOR UPDATE 锁定任务
        AiTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));

        // 2. 跨用户校验（不泄露存在性）
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }

        // 3. 状态校验
        if (task.getStatus() != AiTaskStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态非 SUCCESS，无法确认");
        }
        if (task.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务确认状态非 PENDING");
        }

        // 4. 乐观锁校验
        if (taskUpdatedAt == null || !taskUpdatedAt.equals(task.getUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被其他操作修改，请刷新");
        }

        // 5. 解析草稿
        Map<String, Object> resultJson = task.getResultJson();
        if (resultJson == null || !resultJson.containsKey("draftResumeJson")) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务结果缺少草稿");
        }
        Map<String, Object> draft = (Map<String, Object>) resultJson.get("draftResumeJson");
        List<Map<String, Object>> selected = (List<Map<String, Object>>) resultJson.get("selected");

        // 6. 校验路径有效性
        for (ConfirmedDraftItem item : items) {
            if (pathResolver.resolve(draft, item.outputPath()) == null) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "路径在草稿中不存在: " + item.outputPath());
            }
        }

        // 6b. 选材溯源的两项准入校验（ADR-013）：①selected 的每一处溯源必须被 items 覆盖（否则 40904）；
        // ②selection_reason 归一化、未知值拒绝（40001）。二者都在**任何写库之前**失败，
        // 不依赖事务回滚撤掉已创建的版本。实现在 SelectionProvenance（本类已到行数棘轮上限）。
        // decision != ACCEPT 仍是合法的静默跳过（用户明确拒绝该项）。
        Map<String, ConfirmedDraftItem> decisionsByPath = SelectionProvenance.decisionsByPath(items);
        Map<String, SelectionReason> reasonByPath = SelectionProvenance.validateAndNormalize(selected, items);

        // 7. 标准化
        Map<String, Object> normalized = normalizer.normalize(draft, items);

        // 7b. 合并用户新增事实
        if (additionalResumeJson != null && !additionalResumeJson.isEmpty()) {
            normalized.putAll(additionalResumeJson);
        }

        // 8. EDIT 决策 → 新增 career_material
        List<Long> newMaterialIds = new ArrayList<>();
        Map<String, Long> editedMaterialIds = new LinkedHashMap<>();
        for (ConfirmedDraftItem item : items) {
            if (item.decision() == Decision.EDIT && item.editedValue() != null) {
                CareerMaterial newMat = new CareerMaterial();
                newMat.setUserId(userId);
                newMat.setMaterialType(inferTypeFromPath(item.outputPath()));
                newMat.setTitle("用户确认编辑");
                Map<String, Object> editedContent = stripProvenance(item.editedValue());
                newMat.setContentJson(editedContent);
                newMat.setSourceText(editedContent.toString());
                newMat.setUsagePreference(UsagePreference.NORMAL);
                materialRepository.save(newMat);
                newMaterialIds.add(newMat.getId());
                editedMaterialIds.put(item.outputPath(), newMat.getId());
            }
        }

        // 9. 确定目标简历 ID（支持自动创建岗位简历）
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        Long resumeId = targetResumeIdOverride != null
                ? targetResumeIdOverride
                : toLong(snapshot.get("targetResumeId"));

        if (resumeId == null) {
            // 自动创建岗位简历
            Resume resume = new Resume();
            resume.setUserId(userId);
            resume.setTitle(resolveResumeTitle(resumeTitleOverride, snapshot));
            Long jdId = toLong(snapshot.get("jobDescriptionId"));
            if (jdId != null) {
                resume.setJobDescriptionId(jdId);
            }
            resume = resumeRepository.save(resume);
            resumeId = resume.getId();
        } else {
            // 校验目标简历归属
            Resume target = resumeRepository.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
        }

        if (targetResumeIdOverride != null) {
            Resume target = resumeRepository.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume not found"));
            Long jobDescriptionId = toLong(snapshot.get("jobDescriptionId"));
            if (jobDescriptionId == null || !jobDescriptionId.equals(target.getJobDescriptionId())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "A generated job resume can only update a resume linked to the same job description");
            }
        }

        Map<String, Object> genContext = new LinkedHashMap<>();
        genContext.put("taskId", task.getId());
        genContext.put("taskType", task.getTaskType().name());
        genContext.put("inputSnapshot", snapshot);

        ResumeVersion version = versionService.createInTransaction(
                resumeId, ResumeSourceType.JD_CUSTOMIZED, normalized,
                "岗位定制生成", genContext, userId);

        // 10. 写入最终内容的来源引用。REJECT 不产生引用；EDIT 使用新建的
        // 用户编辑资料，不能继续引用 AI 生成时的原始素材。
        // （decisionsByPath / reasonByPath 已在 6b 构建并校验）
        if (selected != null) {
            for (Map<String, Object> sel : selected) {
                Long materialId = toLong(sel.get("materialId"));
                if (materialId == null) {
                    continue;
                }
                String outputPath = sel.get("outputPath") != null
                        ? sel.get("outputPath").toString() : "";
                ConfirmedDraftItem decision = SelectionProvenance.decisionForSourcePath(outputPath, decisionsByPath);
                if (decision == null || decision.decision() != Decision.ACCEPT) {
                    continue;
                }

                // 读取 CareerMaterial 快照（带 userId 过滤，避免越权读取他人资料；即使后续被软删，快照仍可读）
                Map<String, Object> matSnapshot = materialRepository.findByIdAndUserId(materialId, userId)
                        .map(this::buildSnapshot)
                        .orElse(Map.of("id", materialId, "note", "资料已不存在"));

                // D5：原因已在 6b 统一归一化（未知值在那时就已拒绝，不依赖事务回滚）
                SelectionReason reason = reasonByPath.get(outputPath + "#" + materialId);
                saveReference(version.getId(), materialId, outputPath, matSnapshot,
                        reason == null ? null : reason.dbValue());
            }
        }
        for (Map.Entry<String, Long> edited : editedMaterialIds.entrySet()) {
            CareerMaterial material = materialRepository.findByIdAndUserId(edited.getValue(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Edited material not found"));
            Map<String, Object> editedSnapshot = buildSnapshot(material);
            editedSnapshot.put("origin", "USER_EDITED");
            saveReference(version.getId(), material.getId(), edited.getKey(), editedSnapshot,
                    SelectionReason.USER_EDITED.dbValue());
        }

        // 11. 更新任务状态
        task.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        task.setResultResumeVersionId(version.getId());
        taskRepository.save(task);

        // 12. 收集被拒绝路径
        List<String> rejectedPaths = items.stream()
                .filter(i -> i.decision() == Decision.REJECT)
                .map(ConfirmedDraftItem::outputPath)
                .toList();

        return new CommitResult(version.getId(), version.getVersionNo(),
                rejectedPaths, newMaterialIds, resumeId);
    }

    /**
     * 提交结果。
     */
    public record CommitResult(
            Long resumeVersionId,
            Integer versionNo,
            List<String> rejectedPaths,
            List<Long> newMaterialIds,
            Long resumeId
    ) {}

    // ---- helpers ----

    /**
     * 确定岗位简历标题。优先级：用户指定 > snapshot.resumeTitle > "公司名 - 岗位名" > "岗位定制简历"
     *
     * <p>中间两级是逐级降级的:只有公司名或只有岗位名时用剩下的那个;两者都没有才用兜底名。
     */
    private String resolveResumeTitle(String override, Map<String, Object> snapshot) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        Object snapshotTitle = snapshot.get("resumeTitle");
        if (snapshotTitle != null && !snapshotTitle.toString().isBlank()) {
            return snapshotTitle.toString();
        }
        String company = snapshot.get("companyName") != null
                ? snapshot.get("companyName").toString() : null;
        String position = snapshot.get("positionTitle") != null
                ? snapshot.get("positionTitle").toString() : null;
        if (company != null && position != null) {
            return company + " - " + position;
        }
        if (position != null) {
            return position;
        }
        if (company != null) {
            return company;
        }
        return "岗位定制简历";
    }

    private Map<String, Object> buildSnapshot(CareerMaterial m) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", m.getId());
        snapshot.put("materialType", m.getMaterialType().name());
        snapshot.put("title", m.getTitle());
        snapshot.put("contentJson", m.getContentJson());
        snapshot.put("sourceText", m.getSourceText());
        snapshot.put("usagePreference", m.getUsagePreference().name());
        return snapshot;
    }

    private void saveReference(Long versionId, Long materialId, String outputPath,
                               Map<String, Object> snapshot, String reason) {
        ResumeMaterialReference ref = new ResumeMaterialReference();
        ref.setResumeVersionId(versionId);
        ref.setMaterialId(materialId);
        ref.setSelectionStatus("SELECTED");
        ref.setOutputPath(outputPath);
        ref.setSourceSnapshotJson(snapshot);
        ref.setSelectionReason(reason);
        referenceRepository.save(ref);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stripProvenance(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, child) -> {
            if (!key.equals("_source") && !key.equals("_sources") && !key.equals("_pending")) {
                copy.put(key, stripProvenanceValue(child));
            }
        });
        return copy;
    }

    private Object stripProvenanceValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (key instanceof String name
                        && !name.equals("_source") && !name.equals("_sources") && !name.equals("_pending")) {
                    copy.put(name, stripProvenanceValue(child));
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::stripProvenanceValue).toList();
        }
        return value;
    }

    /**
     * 由草稿路径的**第一段**推断资料类型({@code work[0].x} → WORK_EXPERIENCE)。
     *
     * <p>只认 JSON Resume 的固定章节名;新增可编辑章节时要同步加分支,
     * 否则该章节编辑出的资料会被归成 HIGHLIGHT。
     */
    private MaterialType inferTypeFromPath(String outputPath) {
        if (outputPath == null) {
            return MaterialType.HIGHLIGHT;
        }
        String section = outputPath.split("[.\\[]")[0];
        return switch (section) {
            case "work" -> MaterialType.WORK_EXPERIENCE;
            case "education" -> MaterialType.EDUCATION;
            case "skills" -> MaterialType.SKILL;
            case "projects" -> MaterialType.PROJECT_EXPERIENCE;
            case "certificates" -> MaterialType.CERTIFICATE;
            case "volunteering" -> MaterialType.VOLUNTEER_EXPERIENCE;
            case "courses" -> MaterialType.COURSE;
            case "publications" -> MaterialType.PUBLICATION;
            case "customSections" -> MaterialType.LEADERSHIP_EXPERIENCE;
            default -> MaterialType.HIGHLIGHT;
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
}
