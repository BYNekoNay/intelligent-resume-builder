package com.intelligentresume.export.service;

import com.intelligentresume.common.persistence.AsyncFailureMessages;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Atomically claims PDF tasks so multiple API instances cannot render one task twice.
 *
 * <p>租约服务：负责"领取"以及三种"释放"（成功 / 失败 / 稍后重试）。
 *
 * <p>要点：
 * <ul>
 *     <li>领取用条件更新 {@code acquireLease} 判胜负：只有返回 1 才算抢到，
 *         候选行被别人先抢走时静默跳过，不报错。</li>
 *     <li>{@code retryCount} 在**领取时**自增——它统计的是"尝试次数"，不是"失败次数"。</li>
 *     <li>三种释放都先按 {@code (id, RUNNING, owner)} 加锁复查；查不到说明租约已过期并被他人接管，
 *         本次结果必须丢弃（stale），绝不能覆盖新持有者的进度。</li>
 *     <li>释放成功只写元数据，文件由 worker 先落盘再调用；返回 false 时调用方负责删掉孤儿文件。</li>
 *     <li>{@code nextAttemptAt} 的三种归宿：成功/失败释放清空，容量重排设为未来时刻；
 *         {@code expiresAt}（产物过期时间）在释放阶段不动，只在创建与手动重试时刷新。</li>
 * </ul>
 */
@Service
public class ExportTaskLeaseService {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskLeaseService.class);

    private final ExportTaskRepository exportTaskRepository;
    private final int leaseSeconds;

    public ExportTaskLeaseService(ExportTaskRepository exportTaskRepository,
                                  @Value("${app.pdf.worker.lease-seconds:90}") int leaseSeconds) {
        this.exportTaskRepository = exportTaskRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 领取至多 {@code batchSize} 条任务。
     *
     * <p>{@code owner} 是本次 worker 的身份串（调用方用 {@code pdf-<UUID>}），后续释放校验靠它。
     * 返回的实体同时被改成 RUNNING/带租约，worker 直接用即可，无需回查。
     *
     * <p>注意：{@code claimableTasks} 带 {@code FOR UPDATE}，同批候选会被串行化；真正的互斥仍由
     * {@code acquireLease} 的条件更新兜底。
     */
    @Transactional
    public List<ExportTask> claimBatch(String owner, int batchSize) {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        List<ExportTask> claimed = new ArrayList<>();
        for (ExportTask task : exportTaskRepository.claimableTasks(batchSize)) {
            if (exportTaskRepository.acquireLease(task.getId(), owner, leaseUntil) == 1) {
                task.setStatus(ExportStatus.RUNNING);
                task.setLeaseOwner(owner);
                task.setLeaseExpiresAt(leaseUntil);
                task.setRetryCount(task.getRetryCount() + 1);
                claimed.add(task);
            }
        }
        return claimed;
    }

    /**
     * 释放成功。先按 owner 锁定校验，防止租约过期被其他实例接管后，旧 worker 覆盖新进度（stale 丢弃）。
     *
     * @return true 表示成功写入；false 表示任务已非本 worker 持有，释放被丢弃
     */
    @Transactional
    public boolean releaseSuccess(ExportTask task, ExportStorageService.StoredFile stored) {
        String owner = task.getLeaseOwner();
        ExportTask storedTask = exportTaskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (storedTask == null) {
            log.warn("Discarding stale success for task {} from owner {}", task.getId(), owner);
            return false;
        }
        storedTask.setStatus(ExportStatus.SUCCESS);
        storedTask.setStorageKey(stored.storageKey());
        storedTask.setFileSizeBytes(stored.size());
        storedTask.setSha256(stored.checksumSha256());
        storedTask.setErrorMessage(null);
        storedTask.setNextAttemptAt(null);
        clearLease(storedTask);
        exportTaskRepository.save(storedTask);
        copyExecutionState(storedTask, task);
        return true;
    }

    /**
     * 释放失败。同样按 owner 锁定校验，stale 时丢弃。
     *
     * @return true 表示成功写入；false 表示任务已非本 worker 持有，释放被丢弃
     */
    @Transactional
    public boolean releaseFailed(ExportTask task, String errorMessage) {
        String owner = task.getLeaseOwner();
        ExportTask storedTask = exportTaskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (storedTask == null) {
            log.warn("Discarding stale failure for task {} from owner {}", task.getId(), owner);
            return false;
        }
        storedTask.setStatus(ExportStatus.FAILED);
        storedTask.setErrorMessage(AsyncFailureMessages.persisted(errorMessage));
        storedTask.setNextAttemptAt(null);
        clearLease(storedTask);
        exportTaskRepository.save(storedTask);
        copyExecutionState(storedTask, task);
        return true;
    }

    /**
     * 释放「稍后再试」：任务回到 PENDING，并在 {@code retryAfterSeconds} 内不可被领取。
     *
     * <p>用于 pdf-service 的容量拒绝（503 + {@code Retry-After}）—— 与
     * {@link #releaseFailed} 的区别是「这不是终态」：留痕的 errorMessage 会随下一次
     * 成功或最终失败被覆盖/清空，用户看到的是「排队中」而不是「失败，请手动重试」。
     *
     * @return true 表示成功写入；false 表示任务已非本 worker 持有，释放被丢弃
     */
    @Transactional
    public boolean releaseForRetry(ExportTask task, String errorMessage, int retryAfterSeconds) {
        String owner = task.getLeaseOwner();
        ExportTask storedTask = exportTaskRepository.findRunningByIdAndOwnerForUpdate(task.getId(), owner).orElse(null);
        if (storedTask == null) {
            log.warn("Discarding stale retry request for task {} from owner {}", task.getId(), owner);
            return false;
        }
        storedTask.setStatus(ExportStatus.PENDING);
        storedTask.setErrorMessage(AsyncFailureMessages.persisted(errorMessage));
        storedTask.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.max(1, retryAfterSeconds)));
        clearLease(storedTask);
        exportTaskRepository.save(storedTask);
        copyExecutionState(storedTask, task);
        return true;
    }

    private void clearLease(ExportTask task) {
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
    }

    private void copyExecutionState(ExportTask source, ExportTask target) {
        target.setStatus(source.getStatus());
        target.setErrorMessage(source.getErrorMessage());
        target.setStorageKey(source.getStorageKey());
        target.setFileSizeBytes(source.getFileSizeBytes());
        target.setSha256(source.getSha256());
        target.setLeaseOwner(source.getLeaseOwner());
        target.setLeaseExpiresAt(source.getLeaseExpiresAt());
        target.setNextAttemptAt(source.getNextAttemptAt());
    }
}
