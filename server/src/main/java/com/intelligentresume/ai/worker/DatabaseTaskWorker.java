package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库轮询工作器。定期按任务分组领取并执行 PENDING 的 AI 任务。
 *
 * <p>旧实现是单线程串行:一次只领一条、执行完再领下一条,于是一条分钟级的
 * {@code JOB_GENERATION} 会把同时到达的秒级 {@code INLINE_OPTIMIZE} 饿死数分钟。
 * 现在改为「调度线程串行领取 + 分组线程池并发执行」:领取始终在单个调度线程上
 * 串行发生,执行则按 {@link Group} 提交到各自的专属线程池,并受该分组并发额度约束。
 * 因此重任务额度被占满时,轻任务仍拥有自己的额度与线程池,不会被阻塞。</p>
 *
 * <p>轮询间隔由 {@code app.ai.worker.poll-interval-ms} 控制。
 * 测试环境通过 {@code app.worker-scheduling.enabled=false} 关闭自动调度。</p>
 *
 * <p>要点 / 易错点:
 * <ul>
 *   <li>领取必须保持"单线程串行":同一个 JVM 内只有一个调度线程执行 {@code dispatch},
 *       不要给 {@link #poll()} 加 {@code @Async} 或并行流,否则 {@code inFlight} 计数会失真、
 *       并发额度形同虚设。</li>
 *   <li>{@code inFlight} 是进程内的"已提交未完成"计数:必须在提交前 +1、在任务 finally 里 -1。
 *       漏减会让该分组永久卡在满额,后续任务永远领不到。</li>
 *   <li>一次 poll 里两组共用一个 try:HEAVY 派发抛异常会顺带跳过本轮的 LIGHT,
 *       下一轮才恢复(只丢一个周期,不影响正确性)。</li>
 *   <li>每批领取共用一个 owner 字符串({@code 实例ID:UUID});终态写入要求 owner 与租约一致,
 *       所以不要在派发过程中更换 owner。</li>
 *   <li>线程池在 {@code AiTaskWorkerConfig} 里按分组固定大小,与这里的并发额度是两套独立上限,
 *       只调其中一个不会改变实际并发。</li>
 * </ul>
 */
@Component
public class DatabaseTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTaskWorker.class);

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final TaskLeaseService leaseService;
    private final TaskExecutionService executionService;
    private final AiTaskWorkerProperties properties;
    private final Map<Group, Executor> executors = new EnumMap<>(Group.class);
    private final Map<Group, AtomicInteger> inFlight = new EnumMap<>(Group.class);

    public DatabaseTaskWorker(TaskLeaseService leaseService,
                              TaskExecutionService executionService,
                              AiTaskWorkerProperties properties,
                              @Qualifier("aiTaskHeavyExecutor") Executor heavyExecutor,
                              @Qualifier("aiTaskLightExecutor") Executor lightExecutor) {
        this.leaseService = leaseService;
        this.executionService = executionService;
        this.properties = properties;
        this.executors.put(Group.HEAVY, heavyExecutor);
        this.executors.put(Group.LIGHT, lightExecutor);
        for (Group group : Group.values()) {
            inFlight.put(group, new AtomicInteger());
        }
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.poll-interval-ms}")
    public void poll() {
        try {
            dispatch(Group.HEAVY, properties.getHeavyConcurrency());
            dispatch(Group.LIGHT, properties.getLightConcurrency());
        } catch (Exception e) {
            log.error("Error in AI task worker poll cycle", e);
        }
    }

    /**
     * 为一个分组领取并派发任务:最多领取到「该分组额度 − 在途任务数」为止,
     * 且不超过 {@code batch-size}。领取在本调度线程上串行发生,执行被提交到
     * 该分组的专属线程池,完成时释放额度。
     */
    private void dispatch(Group group, int limit) {
        AtomicInteger running = inFlight.get(group);
        int slots = limit - running.get();
        if (slots <= 0) {
            return;
        }
        int claimSize = Math.min(slots, properties.getBatchSize());
        List<AiTaskType> types = AiTaskCapabilityRegistry.typesIn(group);
        String owner = instanceId + ":" + UUID.randomUUID();
        List<AiTask> claimed = leaseService.claimBatch(owner, claimSize, types);
        if (claimed.isEmpty()) {
            return;
        }
        Executor executor = executors.get(group);
        for (AiTask task : claimed) {
            running.incrementAndGet();
            executor.execute(() -> runTask(task, owner, group, running));
        }
    }

    /**
     * 执行单个已领取的任务。
     *
     * <p>异常在这里被吞掉并记日志:单条任务失败不能让线程池里的 worker 线程死掉,
     * 否则该分组会永久少一个执行槽。额度回收放在 finally,保证异常路径也不漏减。
     */
    private void runTask(AiTask task, String owner, Group group, AtomicInteger running) {
        try {
            executionService.execute(task, owner);
        } catch (Exception e) {
            log.error("Error executing task {}, group={}, continuing with next", task.getId(), group, e);
        } finally {
            running.decrementAndGet();
        }
    }
}