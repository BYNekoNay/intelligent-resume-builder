package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.persistence.AsyncFailureMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
     * 领取指定任务类型集合中可执行的任务。
     * 先查询可领取任务(FOR UPDATE),再逐个尝试获取租约。
     *
     * @param taskTypes 允许领取的任务类型;为空时不做任何领取
     */
    @Transactional
    public List<AiTask> claimBatch(String owner, int batchSize, List<AiTaskType> taskTypes) {
        if (taskTypes == null || taskTypes.isEmpty()) {
            return List.of();
        }
        List<String> typeNames = taskTypes.stream().map(Enum::name).toList();
        List<AiTask> claimable = taskRepository.claimableTasksByTypes(typeNames, batchSize);
        List<AiTask> acquired = new ArrayList<>();
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(properties.getLeaseSeconds());
        // 读一次：上限 <= 0 属非法配置（NumericConfigurationValidator 会拒绝启动），
        // 这里兜底为「关闭收口」，避免 0 值把每一个任务都判死。
        int maxAttempts = properties.getMaxAttempts();

        for (AiTask task : claimable) {
            // 崩溃循环收口：进程被硬杀时任务停在 RUNNING，租约到期后被反复领取，
            // retry_count 一直涨而状态永不落地。达到上限即判 FAILED 并跳过领取 ——
            // 否则一次坏输入会让 worker 无限重跑（此前 claim/acquireLease 都没有这个上界）。
            if (maxAttempts > 0 && task.getRetryCount() >= maxAttempts) {
                int abandoned = taskRepository.abandonExhausted(task.getId(), maxAttempts,
                        AsyncFailureMessages.persisted(
                                "任务连续 " + task.getRetryCount() + " 次被领取但未完成，已终止；请重新发起"));
                log.warn("Abandoning AI task {}: claimed {} times without reaching a terminal state (updated={})",
                        task.getId(), task.getRetryCount(), abandoned);
                continue;
            }
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
        return releaseSuccess(task, owner, result, null);
    }

    /**
     * 释放成功（带同事务副作用钩子）。
     *
     * <p>租约校验通过后、结果写入前，在同一事务内执行 {@code withinTransaction}
     * （例如 #29 沟通草稿落库并把 id 写回结果）。租约已被接管时钩子不会执行，
     * 也不会留下引用不到任何任务的孤儿副作用行。
     */
    @Transactional
    public boolean releaseSuccess(AiTask task, String owner, Map<String, Object> result,
                                  Consumer<Map<String, Object>> withinTransaction) {
        AiTask stored = taskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (stored == null) {
            log.warn("Discarding stale success for task {} from owner {}", task.getId(), owner);
            return false;
        }
        if (withinTransaction != null) {
            withinTransaction.accept(result);
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
     * 无论是否可重试，一律 → FAILED（R33：不自动重试，重试只能由用户发起）。
     */
    @Transactional
    public boolean releaseFailed(AiTask task, String owner, String errorMessage, boolean retryable) {
        AiTask stored = taskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (stored == null) {
            log.warn("Discarding stale failure for task {} from owner {}", task.getId(), owner);
            return false;
        }
        // R33：**一律置 FAILED，不再自动重试**。
        // 理由：失败任务照样占配额，自动重试会在用户不知情时消耗额度；而"换个时刻再试一次"
        // 这个判断本来就该由人做。retryable 仍保留在入参里（供应商确实区分瞬时/永久失败），
        // 这里只用于日志，并把"是否可重试"通过文案/前端重试按钮交回给用户。
        if (retryable) {
            log.info("AI task failed (retryable category, not auto-retried per R33): taskId={}, retryCount={}",
                    stored.getId(), stored.getRetryCount());
        }
        stored.setStatus(AiTaskStatus.FAILED);
        log.debug("Task {} failed: {}", task.getId(), errorMessage);
        stored.setErrorMessage(AsyncFailureMessages.persisted(errorMessage));
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
