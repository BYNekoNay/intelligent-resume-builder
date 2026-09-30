package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>每轮处理一批（默认 200 行、间隔 24h），不追求一次清完；用户侧另有
 * {@code DELETE /api/ai/tasks/history} 可立即清空自己的任务历史。
 */
@Service
public class AiTaskRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRetentionService.class);

    /** 压缩后的快照占位：保留 JSON 合法性，且明确标记内容已清除。 */
    static final Map<String, Object> PURGED_SNAPSHOT = Map.of("_purged", true);

    private final AiTaskRepository taskRepository;
    private final int retentionDays;
    private final int batchSize;

    public AiTaskRetentionService(AiTaskRepository taskRepository,
                                  @Value("${app.ai.task.retention-days:90}") int retentionDays,
                                  @Value("${app.ai.task.cleanup-batch-size:200}") int batchSize) {
        this.taskRepository = taskRepository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    /** 返回本轮压缩的任务数（便于测试与可观测性）。 */
    @Scheduled(fixedDelayString = "${app.ai.task.cleanup-interval-ms:86400000}")
    @Transactional
    public int purgeExpiredSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<AiTask> tasks = taskRepository.findPurgeableForRetention(cutoff, PageRequest.of(0, batchSize));
        for (AiTask task : tasks) {
            task.setInputSnapshotJson(PURGED_SNAPSHOT);
            task.setResultJson(null);
            task.setSnapshotPurged(true);
            taskRepository.save(task);
        }
        if (!tasks.isEmpty()) {
            log.info("Purged inline snapshots of {} AI tasks older than {} days", tasks.size(), retentionDays);
        }
        return tasks.size();
    }
}