package com.intelligentresume.interview.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面试 AI 评估的后台执行器。
 *
 * <p><b>为什么需要这个类</b>：{@code POST /api/interviews/{id}/answer} 原先在**请求线程内
 * 同步等待 AI 评估**。实测 4 轮耗时 102.3 / 76.4 / 110.0 / 145.9s（平均 108.7s），
 * 而前端该接口的超时只有 60s（`web/src/api/interview.ts`）—— <b>4 轮全部超时</b>：
 * 用户提交后看到失败、服务端仍在评估；重试遇 409「当前不在等待回答状态」、
 * 结束遇 409「AI 操作进行中」，于是**卡在无法前进的面试里**。
 * 详见 `docs/reviews/2026-09-25-full-functional-verification.md` §2。
 *
 * <p><b>为什么用后台执行而不是别的手段</b>：这不是新引入的范式 ——
 * 前端**本来就在** {@code EVALUATING_ANSWER} 状态下轮询（`InterviewView.scheduleStatePoll`），
 * 后端也已有 attempt 的 PROCESSING 状态、陈旧超时判定与 {@code /ai/retry} 重试入口，
 * 即"异步评估"本就是半成品设计。把事务外的 AI 段移到本执行器即可，
 * {@code /answer} 立即返回 {@code EVALUATING_ANSWER}，无需改前端。
 *
 * <p><b>有界是关键</b>：宿主机仅 3.6 GiB 且与另一项目共用，不能放开无界线程。
 * 队列满时按 {@link ThreadPoolExecutor.AbortPolicy} 抛出
 * {@link java.util.concurrent.RejectedExecutionException}，由调用方把 attempt 标记为
 * <b>可重试失败</b>并给出明确文案，而不是让任务静默滞留。
 */
@Configuration
public class InterviewEvaluationConfig {

    /** 并发评估线程数：评估是 HTTP 等待型，2 个足够；再大只是把压力转给模型链。 */
    private static final int WORKERS = 2;

    /** 等待队列上限：超出即快速失败并提示重试，避免任务无限堆积。 */
    private static final int QUEUE_CAPACITY = 50;

    /**
     * 供 {@link InterviewAnswerService} 注入（`@Qualifier("interviewEvaluationExecutor")`）。
     *
     * <p>声明为 {@link ExecutorService} 以便 Spring 在关闭时调用 {@code shutdown}；
     * 线程为 daemon 且允许核心线程超时回收，空闲时不占资源。
     * 测试可注入同步执行器（{@code Runnable::run}）以获得确定性。
     */
    @Bean(name = "interviewEvaluationExecutor", destroyMethod = "shutdown")
    public ExecutorService interviewEvaluationExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "interview-eval-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                WORKERS, WORKERS, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
