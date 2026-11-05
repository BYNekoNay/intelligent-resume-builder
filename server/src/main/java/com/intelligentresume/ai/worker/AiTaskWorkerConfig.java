package com.intelligentresume.ai.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 任务执行线程池:每个领取分组一个独立线程池,互不抢占额度。
 *
 * <p>不放进 {@code WorkerSchedulingConfig},因为后者在测试环境下被
 * {@code app.worker-scheduling.enabled=false} 关闭,而工作器 bean 仍需执行器。
 * 池大小取自 {@code app.ai.worker.heavy-concurrency} / {@code light-concurrency}。</p>
 *
 * <p>要点:
 * <ul>
 *   <li>固定大小线程池 + 无界队列:池大小就是硬并发上限,队列积压不会被拒绝,只会排队等待。</li>
 *   <li>池大小至少为 1({@code Math.max(1, size)}),配置成 0 或负数不会让应用起不来,
 *       但会静默变成单线程。</li>
 *   <li>线程是 daemon 且带名字前缀(便于抓线程栈);容器关闭时由 {@code destroyMethod} 关闭池。</li>
 *   <li>这里的池大小与 {@code DatabaseTaskWorker} 里的并发额度是两套独立上限,只调一个不改变实际并发。</li>
 * </ul>
 */
@Configuration
public class AiTaskWorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService aiTaskHeavyExecutor(AiTaskWorkerProperties properties) {
        return fixedPool(properties.getHeavyConcurrency(), "ai-task-heavy-");
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService aiTaskLightExecutor(AiTaskWorkerProperties properties) {
        return fixedPool(properties.getLightConcurrency(), "ai-task-light-");
    }

    private ExecutorService fixedPool(int size, String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(Math.max(1, size), factory);
    }
}