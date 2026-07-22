package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.resume.domain.ResumeMaterialReference;
import com.intelligentresume.resume.repository.ResumeMaterialReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 把用户确认后的草稿 JSON 写入 {@code resume_version},事务性写 {@code ai_task.result_resume_version_id}。
 */
@Service
public class DraftCommitService {

    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final ResumeMaterialReferenceRepository referenceRepository;

    public DraftCommitService(ResumeRepository resumeRepository,
                              ResumeVersionRepository resumeVersionRepository,
                              ResumeMaterialReferenceRepository referenceRepository) {
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.referenceRepository = referenceRepository;
    }

    @Transactional
    public CommitResult commit(AiTask task,
                               List<ConfirmRequest.ConfirmedDraftItem> items,
                               Map<String, Object> additionalResumeJson,
                               Map<String, Object> finalResumeJson,
                               Long userId) {
        // 1. 解析 task -> resume_id
        Object resumeIdRaw = task.getInputSnapshotJson() == null
                ? null : task.getInputSnapshotJson().get("resumeId");
        if (!(resumeIdRaw instanceof Number num)) {
            throw new IllegalStateException("ai_task.input_snapshot_json.resumeId 缺失,无法提交简历版本");
        }
        Resume resume = resumeRepository.findByIdAndUserId(num.longValue(), userId)
                .orElseThrow(() -> new IllegalStateException("简历不存在或不属于当前用户"));

        // 2. 计算 version_no
        int nextVersionNo = resumeVersionRepository
                .findFirstByResumeIdOrderByVersionNoDesc(resume.getId())
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);

        // 3. 写入新版本
        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resume.getId());
        version.setVersionNo(nextVersionNo);
        version.setSourceType(ResumeVersion.SourceType.JD_CUSTOMIZED);
        version.setResumeJson(finalResumeJson);
        version.setGenerationContext(task.getResultJson());
        version.setCreatedBy(userId);
        ResumeVersion saved = resumeVersionRepository.save(version);

        // 4. 更新当前版本
        resume.setCurrentVersionId(saved.getId());
        resumeRepository.save(resume);
        persistMaterialReferences(saved.getId(), task.getResultJson(), items);

        return new CommitResult(saved.getId(), saved.getVersionNo(), new ArrayList<>(), items.size());
    }

    private void persistMaterialReferences(Long versionId, Map<String, Object> result,
                                           List<ConfirmRequest.ConfirmedDraftItem> items) {
        if (result == null) return;
        Map<String, ConfirmRequest.Decision> decisions = new HashMap<>();
        for (ConfirmRequest.ConfirmedDraftItem item : items) decisions.put(item.outputPath(), item.decision());
        Object draft = result.get("draftResumeJson");
        collectReferences(draft, "", versionId, decisions);
    }

    @SuppressWarnings("unchecked")
    private void collectReferences(Object value, String path, Long versionId,
                                   Map<String, ConfirmRequest.Decision> decisions) {
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectReferences(list.get(i), path + "/" + i, versionId, decisions);
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = (Map<String, Object>) raw;
        Object source = map.get("_source");
        if (source instanceof String sourceText) {
            try {
                Long materialId = materialIdFromSource(sourceText);
                if (materialId == null) return;
                ConfirmRequest.Decision decision = decisions.getOrDefault(path, ConfirmRequest.Decision.ACCEPT);
                ResumeMaterialReference reference = new ResumeMaterialReference();
                reference.setResumeVersionId(versionId);
                reference.setMaterialId(materialId);
                reference.setSelectionStatus(decision.name());
                reference.setOutputPath(path);
                reference.setSourceSnapshotJson(new HashMap<>(map));
                reference.setSelectionReason("由用户确认的岗位定制草稿来源");
                referenceRepository.save(reference);
            } catch (NumberFormatException ignored) {
                // Unknown source markers are not persisted as material references.
            }
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!entry.getKey().startsWith("_")) {
                collectReferences(entry.getValue(), path + "/" + entry.getKey(), versionId, decisions);
            }
        }
    }

    private Long materialIdFromSource(String source) {
        String prefix = source.startsWith("material:") ? "material:" : "career-material:";
        if (!source.startsWith(prefix)) return null;
        return Long.valueOf(source.substring(prefix.length()));
    }

    public record CommitResult(
            Long resumeVersionId,
            Integer versionNo,
            List<Long> newMaterialIds,
            int confirmedItemCount
    ) {}
}
