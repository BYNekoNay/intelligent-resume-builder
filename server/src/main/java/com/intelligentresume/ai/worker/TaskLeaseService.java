package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务租约服务。管理任务的领取、续租、释放。
 */
@Service
public class TaskLeaseService {

    private static final Logger log = LoggerFactory.getLogger(TaskLeaseService.class);

    private final AiTaskRepository taskRepository;
    private final AiTaskWorkerProperties properties;

    public TaskLeaseService(AiTaskRepository taskRepository, AiTaskWorkerProperties properties) {
        this.taskRepository = taskRepository;
        this.properties = properties;
    }

    /**
     * 批量领取可执行的任务。
     * 先查询可领取任务(FOR UPDATE SKIP LOCKED),再逐个尝试获取租约。
     */
    @Transactional
    public List<AiTask> claimBatch(String owner, int batchSize) {
        List<AiTask> claimable = taskRepository.claimableTasks(batchSize);
        List<AiTask> acquired = new ArrayList<>();
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(properties.getLeaseSeconds());

        for (AiTask task : claimable) {
            int updated = taskRepository.acquireLease(task.getId(), owner, leaseUntil);
            if (updated > 0) {
                // 刷新实体状态以反映原生 SQL 的更新
                task.setStatus(AiTaskStatus.RUNNING);
                task.setLeaseOwner(owner);
                task.setLeaseExpiresAt(leaseUntil);
                task.setRetryCount(task.getRetryCount() + 1);
                acquired.add(task);
            }
        }

        if (!acquired.isEmpty()) {
            log.debug("Worker {} claimed {} tasks", owner, acquired.size());
        }
        return acquired;
    }

    /**
     * 续租:延长任务的租约过期时间。
     */
    @Transactional
    public boolean renew(Long taskId, String owner) {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(properties.getLeaseSeconds());
        return taskRepository.renewLease(taskId, owner, leaseUntil) == 1;
    }

    /**
     * 释放成功:设置 SUCCESS 状态和结果,清除租约。
     */
    @Transactional
    public boolean releaseSuccess(AiTask task, String owner, Map<String, Object> result) {
        AiTask stored = taskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (stored == null) {
            log.warn("Discarding stale success for task {} from owner {}", task.getId(), owner);
            return false;
        }
        stored.setStatus(AiTaskStatus.SUCCESS);
        stored.setResultJson(result);
        stored.setConfirmationStatus(task.getConfirmationStatus());
        stored.setLeaseOwner(null);
        stored.setLeaseExpiresAt(null);
        stored.setErrorMessage(null);
        taskRepository.save(stored);
        copyExecutionState(stored, task);
        log.debug("Task {} completed successfully", task.getId());
        return true;
    }

    /**
     * 释放失败:根据是否可重试和重试次数决定状态。
     * 可重试且未超过最大重试次数 → PENDING(等待下次领取);否则 → FAILED。
     */
    @Transactional
    public boolean releaseFailed(AiTask task, String owner, String errorMessage, boolean retryable) {
        AiTask stored = taskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (stored == null) {
            log.warn("Discarding stale failure for task {} from owner {}", task.getId(), owner);
            return false;
        }
        if (retryable && stored.getRetryCount() < properties.getMaxRetries()) {
            stored.setStatus(AiTaskStatus.PENDING);
            log.debug("Task {} failed (retryable), reset to PENDING (retry {}/{})",
                    task.getId(), stored.getRetryCount(), properties.getMaxRetries());
        } else {
            stored.setStatus(AiTaskStatus.FAILED);
            log.debug("Task {} failed permanently: {}", task.getId(), errorMessage);
        }
        stored.setErrorMessage(errorMessage);
        stored.setLeaseOwner(null);
        stored.setLeaseExpiresAt(null);
        taskRepository.save(stored);
        copyExecutionState(stored, task);
        return true;
    }

    private void copyExecutionState(AiTask source, AiTask target) {
        target.setStatus(source.getStatus());
        target.setResultJson(source.getResultJson());
        target.setConfirmationStatus(source.getConfirmationStatus());
        target.setErrorMessage(source.getErrorMessage());
        target.setLeaseOwner(source.getLeaseOwner());
        target.setLeaseExpiresAt(source.getLeaseExpiresAt());
    }
}
