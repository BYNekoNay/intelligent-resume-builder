package com.intelligentresume.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 启用 {@code @Scheduled} 异步轮询(T06 工作器、T10 PDF 工作器复用)。
 * 配置 4 线程池,避免单线程调度器阻塞多个 Worker。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.worker-scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerSchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("worker-sched-");
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
