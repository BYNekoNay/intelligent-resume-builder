package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 任务保留期清理（ideation #26）。
 *
 * <p>任务行内联保存了提示词快照（内联 JD / 资料 / 简历，可达数百 KB）与结果 JSON，
 * 且没有 TTL——长期运行会单调增长。策略（与用户确认口径）：
 * <ul>
 *     <li>超过 {@code app.ai.task.retention-days}（默认 90 天）的**终态**任务压缩为元数据：
 *     快照替换为占位 JSON、结果置空，保留 id/类型/状态/时间/幂等键等行数据；</li>
 *     <li>待确认（SUCCESS + confirmationStatus=PENDING）与进行中的任务不压缩；</li>
 *     <li>压缩是一次性的：{@code snapshot_purged} 置位后不再被选中（占位 JSON 无 SQL 可判定特征）。</li>
 * </ul>
 *
 * <p>每轮**续批**处理直到清完或达到单轮上限（默认 20000 行），每批一批一事务（默认 200 行、
 * 间隔 24h）。此前实现每轮只处理一批且没有续批：清理速率被硬编码为 200 行/天，而间隔是 24h
 * ——一旦「每天新超期的任务数 > 200」，积压就单调增长、90 天保留期对超出的部分永不生效
 * （快照含内联 JD/资料/简历，可达数百 KB/行）。用户侧另有 {@code DELETE /api/ai/tasks/history}
 * 可立即清空自己的任务历史。
 */
@Service
public class AiTaskRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRetentionService.class);

    /** 压缩后的快照占位：保留 JSON 合法性，且明确标记内容已清除。 */
    static final Map<String, Object> PURGED_SNAPSHOT = Map.of("_purged", true);

    private final AiTaskRepository taskRepository;
    private final TransactionTemplate transactionTemplate;
    private final int retentionDays;
    private final int batchSize;
    private final int maxRowsPerRun;

    public AiTaskRetentionService(AiTaskRepository taskRepository,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.ai.task.retention-days:90}") int retentionDays,
                                  @Value("${app.ai.task.cleanup-batch-size:200}") int batchSize,
                                  @Value("${app.ai.task.cleanup-max-rows-per-run:20000}") int maxRowsPerRun) {
        this.taskRepository = taskRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxRowsPerRun = maxRowsPerRun;
    }

    /** 返回本轮压缩的任务数（便于测试与可观测性）；单轮续批直到清完或触达上限。 */
    @Scheduled(fixedDelayString = "${app.ai.task.cleanup-interval-ms:86400000}")
    public int purgeExpiredSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int total = 0;
        while (total < maxRowsPerRun) {
            // 逐批独立事务：单事务内累积上万行会让持久化上下文与持锁时间随积压线性增长
            int limit = Math.min(batchSize, maxRowsPerRun - total);
            Integer purged = transactionTemplate.execute(status -> purgeBatch(cutoff, limit));
            int purgedCount = purged == null ? 0 : purged;
            if (purgedCount == 0) {
                break;
            }
            total += purgedCount;
        }
        if (total > 0) {
            log.info("Purged inline snapshots of {} AI tasks older than {} days", total, retentionDays);
        }
        if (total >= maxRowsPerRun) {
            log.warn("AI task retention stopped at the per-run cap of {} rows; the remaining backlog "
                    + "drains on the next run", maxRowsPerRun);
        }
        return total;
    }

    /** 压缩一批（调用方保证事务边界）。 */
    private int purgeBatch(LocalDateTime cutoff, int limit) {
        List<AiTask> tasks = taskRepository.findPurgeableForRetention(cutoff, PageRequest.of(0, limit));
        for (AiTask task : tasks) {
            task.setInputSnapshotJson(PURGED_SNAPSHOT);
            task.setResultJson(null);
            task.setSnapshotPurged(true);
            taskRepository.save(task);
        }
        return tasks.size();
    }
}