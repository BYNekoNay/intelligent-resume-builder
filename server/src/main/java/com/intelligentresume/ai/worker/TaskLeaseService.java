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
    public void renew(AiTask task, String owner) {
        task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(properties.getLeaseSeconds()));
        taskRepository.save(task);
    }

    /**
     * 释放成功:设置 SUCCESS 状态和结果,清除租约。
     */
    @Transactional
    public void releaseSuccess(AiTask task, Map<String, Object> result) {
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setResultJson(result);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        task.setErrorMessage(null);
        taskRepository.save(task);
        log.debug("Task {} completed successfully", task.getId());
    }

    /**
     * 释放失败:根据是否可重试和重试次数决定状态。
     * 可重试且未超过最大重试次数 → PENDING(等待下次领取);否则 → FAILED。
     */
    @Transactional
    public void releaseFailed(AiTask task, String errorMessage, boolean retryable) {
        if (retryable && task.getRetryCount() < properties.getMaxRetries()) {
            task.setStatus(AiTaskStatus.PENDING);
            log.debug("Task {} failed (retryable), reset to PENDING (retry {}/{})",
                    task.getId(), task.getRetryCount(), properties.getMaxRetries());
        } else {
            task.setStatus(AiTaskStatus.FAILED);
            log.debug("Task {} failed permanently: {}", task.getId(), errorMessage);
        }
        task.setErrorMessage(errorMessage);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        taskRepository.save(task);
    }
}
