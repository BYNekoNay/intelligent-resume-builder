package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExportExpiryService {
    private static final Logger log = LoggerFactory.getLogger(ExportExpiryService.class);

    private final ExportTaskRepository taskRepository;
    private final ExportStorageService storageService;
    private final int batchSize;

    public ExportExpiryService(ExportTaskRepository taskRepository, ExportStorageService storageService,
                               @Value("${app.pdf.cleanup-batch-size:100}") int batchSize) {
        this.taskRepository = taskRepository;
        this.storageService = storageService;
        this.batchSize = batchSize;
    }

    @Transactional
    public boolean expireIfDue(Long taskId, LocalDateTime now) {
        ExportTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null) return false;
        if (task.getStatus() == ExportStatus.EXPIRED) return true;
        if (task.getStatus() != ExportStatus.SUCCESS || task.getExpiresAt() == null || !task.getExpiresAt().isBefore(now)) {
            return false;
        }
        return deleteAndExpire(task);
    }

    @Scheduled(fixedDelayString = "${app.pdf.cleanup-interval-ms:60000}")
    @Transactional
    public void cleanupExpired() {
        List<ExportTask> tasks = taskRepository.findExpiredSuccessfulForUpdate(
                LocalDateTime.now(), PageRequest.of(0, batchSize));
        int cleaned = 0;
        for (ExportTask task : tasks) {
            if (deleteAndExpire(task)) cleaned++;
        }
        if (cleaned > 0) log.info("Deleted {} expired PDF export files", cleaned);
    }

    private boolean deleteAndExpire(ExportTask task) {
        if (!storageService.delete(task.getStorageKey())) {
            log.warn("Will retry cleanup for expired export task {}", task.getId());
            return false;
        }
        task.setStatus(ExportStatus.EXPIRED);
        task.setStorageKey(null);
        task.setFileSizeBytes(null);
        task.setSha256(null);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        taskRepository.save(task);
        return true;
    }
}
