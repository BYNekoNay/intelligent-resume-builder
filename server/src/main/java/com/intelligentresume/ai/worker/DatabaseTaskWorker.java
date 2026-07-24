package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 数据库轮询工作器。定期领取并执行 PENDING 状态的 AI 任务。
 *
 * <p>轮询间隔由 {@code app.ai.worker.poll-interval-ms} 控制。
 * 测试环境设为 60000ms 以避免干扰集成测试。
 */
@Component
public class DatabaseTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTaskWorker.class);

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final TaskLeaseService leaseService;
    private final TaskExecutionService executionService;
    private final AiTaskWorkerProperties properties;

    public DatabaseTaskWorker(TaskLeaseService leaseService,
                              TaskExecutionService executionService,
                              AiTaskWorkerProperties properties) {
        this.leaseService = leaseService;
        this.executionService = executionService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.poll-interval-ms}")
    public void poll() {
        String owner = instanceId + ":" + UUID.randomUUID();
        try {
            List<AiTask> tasks = leaseService.claimBatch(owner, properties.getBatchSize());
            for (AiTask task : tasks) {
                try {
                    executionService.execute(task, owner);
                } catch (Exception e) {
                    log.error("Error executing task {}, continuing with next", task.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error in AI task worker poll cycle", e);
        }
    }
}
