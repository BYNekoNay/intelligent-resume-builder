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