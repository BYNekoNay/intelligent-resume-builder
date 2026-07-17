package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.dto.ConfirmResponse;
import com.intelligentresume.ai.confirmation.dto.RejectRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确认/拒绝领域服务。
 *
 * <p>幂等键 + 乐观锁 + 事务一致性:
 * <ol>
 *     <li>若任务已有 resultResumeVersionId(已确认过),直接幂等返回旧版本</li>
 *     <li>否则 SELECT FOR UPDATE 锁任务,校验 taskUpdatedAt 一致性,逐项合并 ACCEPT/EDIT/REJECT</li>
 *     <li>删除草稿 JSON 中的 _source / _pending 标记,落入 resume_version</li>
 * </ol>
 */
@Service
public class ConfirmationService {

    private final AiTaskRepository taskRepository;
    private final DraftCommitService commitService;
    private final ResumeJsonNormalizer normalizer;

    @PersistenceContext
    private EntityManager entityManager;

    public ConfirmationService(AiTaskRepository taskRepository,
                               DraftCommitService commitService,
                               ResumeJsonNormalizer normalizer) {
        this.taskRepository = taskRepository;
        this.commitService = commitService;
        this.normalizer = normalizer;
    }

    @Transactional
    public ConfirmResponse confirm(Long taskId, ConfirmRequest request, String idempotencyKey, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key 不能为空");
        }

        // 用 JPA 悲观锁加载任务
        AiTask task = entityManager.find(AiTask.class, taskId, LockModeType.PESSIMISTIC_WRITE);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 幂等:已经确认过,直接返回旧版本
        if (task.getConfirmationStatus() == AiTask.ConfirmationStatus.CONFIRMED
                && task.getResultResumeVersionId() != null) {
            return new ConfirmResponse(task.getResultResumeVersionId(),
                    null, task.getResultResumeVersionId(),
                    List.of(), List.of());
        }

        if (task.getStatus() != AiTask.TaskStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务尚未成功,不能确认");
        }

        // taskUpdatedAt 乐观锁校验
        if (task.getConfirmationStatus() != AiTask.ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task is not awaiting confirmation");
        }
        Map<String, Object> validatedDraft = task.getResultJson() == null
                ? Map.of()
                : (Map<String, Object>) task.getResultJson().get("draftResumeJson");
        if (validatedDraft == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Draft is empty");
        }
        ensurePendingItemsAreDecided(validatedDraft, request.items());
        if (task.getUpdatedAt() == null || !task.getUpdatedAt().equals(request.taskUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被更新,请刷新后再确认");
        }

        // 合并草稿:拿原始 draft,按 items 路径应用 ACCEPT(原值)/EDIT(替换)/REJECT(丢弃)
        Map<String, Object> sourceDraft = task.getResultJson() == null
                ? Map.of()
                : (Map<String, Object>) task.getResultJson().get("draftResumeJson");
        if (sourceDraft == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "草稿为空,无法确认");
        }

        Object merged = applyDecisions(sourceDraft, request.items());
        if (request.additionalResumeJson() != null) {
            merged = deepMerge(merged, request.additionalResumeJson());
        }
        Map<String, Object> finalJson = normalizer.normalize((Map<String, Object>) merged);

        DraftCommitService.CommitResult result = commitService.commit(task, request.items(),
                request.additionalResumeJson(), finalJson, userId);

        task.setConfirmationStatus(AiTask.ConfirmationStatus.CONFIRMED);
        task.setResultResumeVersionId(result.resumeVersionId());
        taskRepository.save(task);

        return new ConfirmResponse(result.resumeVersionId(), result.versionNo(),
                result.resumeVersionId(),
                collectRejected(request.items()), result.newMaterialIds());
    }

    @Transactional
    public void reject(Long taskId, RejectRequest request, Long userId) {
        AiTask task = entityManager.find(AiTask.class, taskId, LockModeType.PESSIMISTIC_WRITE);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (task.getStatus() != AiTask.TaskStatus.SUCCESS
                || task.getConfirmationStatus() != AiTask.ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task is not awaiting confirmation");
        }
        if (task.getUpdatedAt() == null || !task.getUpdatedAt().equals(request.taskUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被更新,请刷新后再拒绝");
        }
        task.setConfirmationStatus(AiTask.ConfirmationStatus.REJECTED);
        taskRepository.save(task);
    }

    /**
     * 按 outputPath(JSON Pointer 风格: {@code /basics/name} 形式)对 draft 进行三类决策。
     */
    private Object applyDecisions(Object root, List<ConfirmRequest.ConfirmedDraftItem> items) {
        for (ConfirmRequest.ConfirmedDraftItem item : items) {
            if (item.decision() == ConfirmRequest.Decision.REJECT) {
                removeByPath(root, item.outputPath());
            } else if (item.decision() == ConfirmRequest.Decision.EDIT) {
                setByPath(root, item.outputPath(), item.editedValue());
            } // ACCEPT = 保留原值,不动
        }
        return root;
    }

    private void ensurePendingItemsAreDecided(Map<String, Object> draft,
                                              List<ConfirmRequest.ConfirmedDraftItem> items) {
        List<String> pendingPaths = new ArrayList<>();
        collectPendingPaths(draft, "", pendingPaths);
        for (String path : pendingPaths) {
            boolean decided = items.stream().anyMatch(item -> path.equals(item.outputPath()));
            if (!decided) {
                throw new BusinessException(ErrorCode.CONFLICT, "All pending draft items require a decision");
            }
        }
    }

    private void collectPendingPaths(Object value, String path, List<String> pendingPaths) {
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectPendingPaths(list.get(index), path + "/" + index, pendingPaths);
            }
            return;
        }
        if (!(value instanceof Map<?, ?> map)) return;
        if (map.containsKey("_pending") && !path.isBlank()) {
            pendingPaths.add(path);
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!key.startsWith("_")) {
                collectPendingPaths(entry.getValue(), path + "/" + encodePointerSegment(key), pendingPaths);
            }
        }
    }

    private String encodePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private void removeByPath(Object root, String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return;
        String[] segs = path.substring(1).split("/");
        Object cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            if (cur instanceof Map<?, ?> map) {
                cur = map.get(decode(segs[i]));
            } else if (cur instanceof List<?> list) {
                cur = list.get(safeIndex(segs[i], list.size()));
            } else {
                return;
            }
            if (cur == null) return;
        }
        String last = decode(segs[segs.length - 1]);
        if (cur instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).remove(last);
        } else if (cur instanceof List<?> list) {
            int idx = safeIndex(last, list.size());
            if (idx >= 0 && idx < list.size()) ((List<Object>) list).remove(idx);
        }
    }

    private void setByPath(Object root, String path, Object value) {
        if (path == null || path.isBlank() || "/".equals(path)) return;
        String[] segs = path.substring(1).split("/");
        Object cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            if (!(cur instanceof Map<?, ?>) && !(cur instanceof List<?>)) return;
            if (cur instanceof Map<?, ?>) {
                cur = ((Map<String, Object>) cur).get(decode(segs[i]));
            } else {
                cur = ((List<Object>) cur).get(safeIndex(segs[i], ((List<?>) cur).size()));
            }
            if (cur == null) return;
        }
        String last = decode(segs[segs.length - 1]);
        if (cur instanceof Map<?, ?>) {
            ((Map<String, Object>) cur).put(last, value);
        }
    }

    private String decode(String seg) {
        return seg.replace("~1", "/").replace("~0", "~");
    }

    private int safeIndex(String seg, int size) {
        try { return Math.min(Math.max(Integer.parseInt(seg), 0), size - 1); }
        catch (NumberFormatException e) { return -1; }
    }

    private Object deepMerge(Object a, Object b) {
        if (!(a instanceof Map<?, ?>) || !(b instanceof Map<?, ?>)) return b;
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) a);
        for (Map.Entry<?, ?> e : ((Map<?, ?>) b).entrySet()) {
            String key = String.valueOf(e.getKey());
            out.put(key, out.containsKey(key) ? deepMerge(out.get(key), e.getValue()) : e.getValue());
        }
        return out;
    }

    private List<String> collectRejected(List<ConfirmRequest.ConfirmedDraftItem> items) {
        List<String> rejected = new ArrayList<>();
        for (var i : items) {
            if (i.decision() == ConfirmRequest.Decision.REJECT) {
                rejected.add(i.outputPath());
            }
        }
        return rejected;
    }
}
