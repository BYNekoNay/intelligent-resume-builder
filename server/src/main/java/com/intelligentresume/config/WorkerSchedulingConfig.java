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
 *
 * <p>【小白导览】后台定时任务的总开关与线程池：AI 任务轮询、PDF 渲染轮询、数据清理作业
 * 都靠 {@code @Scheduled} 定时跑，Spring 默认只有 1 个线程，一个任务慢会拖住全部——
 * 这里换成 4 线程的池。改坏它 → 后台任务全部停摆（AI 生成没人处理、清理不跑），
 * 或池被打满导致定时任务集体延迟。
 *
 * <p>注意：
 * <ul>
 *   <li>总开关是 {@code app.worker-scheduling.enabled}（缺省为 true）。置 false 会**同时停掉**
 *       所有 @Scheduled 任务——包括 retention/account-purge 的清理作业，不只是 worker 轮询。</li>
 *   <li>池大小固定 4，所有定时任务共享；新增阻塞型任务前先评估是否会占满线程导致其它任务延迟。</li>
 *   <li>线程名前缀 {@code worker-sched-} 是排障锚点（线程栈/日志），改名会让既有排查手册失效。</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.worker-scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerSchedulingConfig implements SchedulingConfigurer {

    /** 用自定义调度器替换 Spring 默认的单线程 scheduler；initialize() 必须调用，否则池不会创建。 */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("worker-sched-");
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
