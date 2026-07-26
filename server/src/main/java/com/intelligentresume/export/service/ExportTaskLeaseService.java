package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Atomically claims PDF tasks so multiple API instances cannot render one task twice. */
@Service
public class ExportTaskLeaseService {

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

    @Transactional
    public void releaseSuccess(ExportTask task, ExportStorageService.StoredFile stored) {
        task.setStatus(ExportStatus.SUCCESS);
        task.setStorageKey(stored.storageKey());
        task.setFileSizeBytes(stored.size());
        task.setSha256(stored.checksumSha256());
        task.setErrorMessage(null);
        clearLease(task);
        exportTaskRepository.save(task);
    }

    @Transactional
    public void releaseFailed(ExportTask task, String errorMessage) {
        task.setStatus(ExportStatus.FAILED);
        task.setErrorMessage(errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage);
        clearLease(task);
        exportTaskRepository.save(task);
    }

    private void clearLease(ExportTask task) {
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
    }
}
