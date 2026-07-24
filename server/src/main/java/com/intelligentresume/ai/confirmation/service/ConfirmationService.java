package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.dto.ConfirmResponse;
import com.intelligentresume.ai.confirmation.dto.RejectRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 确认编排服务。处理幂等、委派 {@link DraftCommitService} 完成事务性提交。
 *
 * <p>幂等策略（MVP）：同 task 的 confirm 重放走"已 CONFIRMED"分支返回原版本，
 * 不新建幂等表。
 */
@Service
public class ConfirmationService {

    private final AiTaskRepository taskRepository;
    private final DraftCommitService draftCommitService;
    private final ResumeVersionRepository versionRepository;

    public ConfirmationService(AiTaskRepository taskRepository,
                               DraftCommitService draftCommitService,
                               ResumeVersionRepository versionRepository) {
        this.taskRepository = taskRepository;
        this.draftCommitService = draftCommitService;
        this.versionRepository = versionRepository;
    }

    /**
     * 确认草稿。
     *
     * @param taskId         任务 ID
     * @param req            确认请求（含 taskUpdatedAt 乐观锁 + 逐条决策 + 可选简历标题/目标简历）
     * @param idempotencyKey 幂等键（必填）
     * @param userId         当前用户 ID
     * @return 确认响应（含版本号 + 简历 ID）
     */
    public ConfirmResponse confirm(Long taskId, ConfirmRequest req,
                                   String idempotencyKey, Long userId) {
        // 1. 幂等键必填
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少 Idempotency-Key");
        }

        // 2. 快速读取任务（无锁，用于幂等判断）
        AiTask task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));

        // 3. 幂等：已 CONFIRMED 且有版本 → 返回原版本
        if (task.getConfirmationStatus() == ConfirmationStatus.CONFIRMED
                && task.getResultResumeVersionId() != null) {
            ResumeVersion version = versionRepository.findById(task.getResultResumeVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));
            return new ConfirmResponse(
                    version.getId(), version.getVersionNo(),
                    version.getId(), List.of(), List.of(), version.getResumeId());
        }

        // 4. 委派 DraftCommitService 事务性提交
        DraftCommitService.CommitResult result = draftCommitService.commit(
                taskId, req.items(), req.additionalResumeJson(),
                req.taskUpdatedAt(), userId,
                req.resumeTitle(), req.targetResumeId());

        return new ConfirmResponse(
                result.resumeVersionId(), result.versionNo(),
                result.resumeVersionId(), result.rejectedPaths(),
                result.newMaterialIds(), result.resumeId());
    }

    /**
     * 拒绝草稿。不创建 resume_version。
     *
     * @param taskId 任务 ID
     * @param req    拒绝请求（含 taskUpdatedAt 乐观锁）
     * @param userId 当前用户 ID
     */
    @Transactional
    public void reject(Long taskId, RejectRequest req, Long userId) {
        // SELECT FOR UPDATE 防止并发
        AiTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "任务不存在"));

        // 跨用户校验
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }

        // 状态校验
        if (task.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务确认状态非 PENDING，无法拒绝");
        }

        // 乐观锁校验
        if (req.taskUpdatedAt() == null || !req.taskUpdatedAt().equals(task.getUpdatedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被其他操作修改，请刷新");
        }

        // 更新为 REJECTED
        task.setConfirmationStatus(ConfirmationStatus.REJECTED);
        taskRepository.save(task);
    }
}
