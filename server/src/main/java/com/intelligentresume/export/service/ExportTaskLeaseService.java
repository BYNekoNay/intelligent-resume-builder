package com.intelligentresume.export.service;

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

/** Atomically claims PDF tasks so multiple API instances cannot render one task twice. */
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
        storedTask.setErrorMessage(errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage);
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
    }
}
