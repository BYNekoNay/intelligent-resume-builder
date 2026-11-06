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

/**
 * 导出产物过期处理：删文件 + 把任务落成 EXPIRED。
 *
 * <p>职责一句话：保证"过期后既下不到文件，也不再占着存储"。
 * 导出文件不是永久保存的（默认 24 小时，见 {@code app.pdf.file-ttl-hours}）；
 * 到期后本类分两条路清理：定时批量扫（{@link #cleanupExpired}）+ 用户查询/下载时顺手惰性清理（{@link #expireIfDue}）。
 *
 * <p>要点：
 * <ul>
 *     <li>只有 {@code SUCCESS} 且 {@code expiresAt < now} 的行才会过期；PENDING/RUNNING/FAILED 不碰。</li>
 *     <li>删除文件失败时**不改状态**并返回 false，留给下一轮重试——先把文件删了再改状态是顺序红线
 *         （反过来会出现"状态说已过期、文件却还在"的悬空行）。</li>
 *     <li>已经 EXPIRED 的行直接返回 true（幂等），重复调用不会重复删。</li>
 *     <li>行锁（{@code findByIdForUpdate}）避免与其它状态更新路径互相覆盖。</li>
 * </ul>
 */
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

    /**
     * 按需过期单条任务（下载/查询时的惰性清理）。
     *
     * @return true 表示该任务现在是 EXPIRED（本次刚过期，或本来就是）；
     *         false 表示不满足过期条件或删文件失败，调用方应回查数据库最新状态
     */
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

    /**
     * 定时批量清理：每轮最多处理 {@code app.pdf.cleanup-batch-size} 条（默认 100），
     * 删除失败的行保持 SUCCESS 状态，下一轮还会被捞到。
     */
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
