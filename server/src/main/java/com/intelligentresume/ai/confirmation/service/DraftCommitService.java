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
     * 事务性提交确认结果。
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

        // 7. 标准化
        Map<String, Object> normalized = normalizer.normalize(draft, items);

        // 7b. 合并用户新增事实
        if (additionalResumeJson != null && !additionalResumeJson.isEmpty()) {
            normalized.putAll(additionalResumeJson);
        }

        // 8. EDIT 决策 → 新增 career_material
        List<Long> newMaterialIds = new ArrayList<>();
        for (ConfirmedDraftItem item : items) {
            if (item.decision() == Decision.EDIT && item.editedValue() != null) {
                CareerMaterial newMat = new CareerMaterial();
                newMat.setUserId(userId);
                newMat.setMaterialType(inferTypeFromPath(item.outputPath()));
                newMat.setTitle("用户确认编辑");
                newMat.setContentJson(item.editedValue());
                newMat.setSourceText(item.editedValue().toString());
                newMat.setUsagePreference(UsagePreference.NORMAL);
                materialRepository.save(newMat);
                newMaterialIds.add(newMat.getId());
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
            resumeRepository.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
        }

        Map<String, Object> genContext = new LinkedHashMap<>();
        genContext.put("taskId", task.getId());
        genContext.put("taskType", task.getTaskType().name());
        genContext.put("inputSnapshot", snapshot);

        ResumeVersion version = versionService.createInTransaction(
                resumeId, ResumeSourceType.JD_CUSTOMIZED, normalized,
                "岗位定制生成", genContext, userId);

        // 10. 写入 resume_material_reference
        if (selected != null) {
            for (Map<String, Object> sel : selected) {
                Long materialId = toLong(sel.get("materialId"));
                if (materialId == null) {
                    continue;
                }
                String outputPath = sel.get("outputPath") != null
                        ? sel.get("outputPath").toString() : "";

                // 读取 CareerMaterial 快照（即使后续被软删，快照仍可读）
                Map<String, Object> matSnapshot = materialRepository.findById(materialId)
                        .map(this::buildSnapshot)
                        .orElse(Map.of("id", materialId, "note", "资料已不存在"));

                ResumeMaterialReference ref = new ResumeMaterialReference();
                ref.setResumeVersionId(version.getId());
                ref.setMaterialId(materialId);
                ref.setSelectionStatus("SELECTED");
                ref.setOutputPath(outputPath);
                ref.setSourceSnapshotJson(matSnapshot);
                ref.setSelectionReason(sel.get("selectedReason") != null
                        ? sel.get("selectedReason").toString() : null);
                referenceRepository.save(ref);
            }
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
