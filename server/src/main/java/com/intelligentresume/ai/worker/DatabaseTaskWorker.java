package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 数据库任务工作器。
 *
 * <ul>
 *     <li>轮询 PENDING 任务与过期租约的 RUNNING 任务(续租)</li>
 *     <li>占用任务后置状态为 RUNNING + 写 leaseOwner / leaseExpiresAt</li>
 *     <li>驱动 {@link JobGenerationService}</li>
 * </ul>
 */
@Component
public class DatabaseTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTaskWorker.class);

    private final AiTaskRepository repository;
    private final JobGenerationService jobGenerationService;
    private final int batchSize;
    private final int leaseSeconds;

    public DatabaseTaskWorker(AiTaskRepository repository,
                              JobGenerationService jobGenerationService,
                              @Value("${app.ai.worker.batch-size:5}") int batchSize,
                              @Value("${app.ai.worker.lease-seconds:60}") int leaseSeconds) {
        this.repository = repository;
        this.jobGenerationService = jobGenerationService;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.poll-interval-ms:1000}")
    public void poll() {
        List<Long> claimableIds = findClaimableIds();
        if (claimableIds.isEmpty()) return;
        for (Long taskId : claimableIds) {
            try {
                boolean claimed = claim(taskId);
                if (!claimed) continue;
                jobGenerationService.run(taskId);
            } catch (Exception ex) {
                log.warn("Worker task {} failed: {}", taskId, ex.toString());
                markFailed(taskId, ex.getMessage());
            }
        }
    }

    private List<Long> findClaimableIds() {
        return repository.findClaimableBatch(LocalDateTime.now(), PageRequest.of(0, batchSize))
                .stream().map(AiTask::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long taskId) {
        return repository.findById(taskId).map(task -> {
            if (task.getStatus() != AiTask.TaskStatus.PENDING
                    && !(task.getStatus() == AiTask.TaskStatus.RUNNING
                    && task.getLeaseExpiresAt() != null
                    && task.getLeaseExpiresAt().isBefore(LocalDateTime.now()))) {
                return false;
            }
            task.setStatus(AiTask.TaskStatus.RUNNING);
            task.setLeaseOwner("worker-" + UUID.randomUUID());
            task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(leaseSeconds));
            repository.save(task);
            return true;
        }).orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String reason) {
        repository.findById(taskId).ifPresent(task -> {
            task.setStatus(AiTask.TaskStatus.FAILED);
            task.setErrorMessage(truncate(reason));
            task.setLeaseExpiresAt(null);
            repository.save(task);
        });
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
