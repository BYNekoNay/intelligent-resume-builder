package com.intelligentresume.common.observability;

import com.intelligentresume.common.api.TraceIdFilter;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 异步 worker 的 trace 上下文：每次执行新开一个 {@code worker-} 前缀的 traceId，并带上 executionId/taskId。
 *
 * <p>【小白导览】后台任务（AI 生成、PDF 渲染等 worker）不在 HTTP 请求里跑，没有 TraceIdFilter
 * 给它发追踪号——本类在每次任务执行前手工发一个（前缀 worker- 便于和网页请求区分）。
 * 改坏它 → 后台任务日志串号或无号，排查靠猜。
 *
 * <p>用法必须是 try-with-resources（实现 {@link AutoCloseable}）：close 会按打开的相反顺序还原 MDC，
 * 否则调度线程被复用后，下一个任务的日志会串上上一个任务的 traceId。异步线程没有请求上下文，
 * 因此不能复用 {@code TraceIdFilter} 的 id。
 */
public final class WorkerTraceContext implements AutoCloseable {

    private final MDC.MDCCloseable traceId;
    private final MDC.MDCCloseable executionId;
    private final MDC.MDCCloseable taskId;

    private WorkerTraceContext(String taskIdValue) {
        String id = UUID.randomUUID().toString();
        this.traceId = MDC.putCloseable(TraceIdFilter.TRACE_ID_MDC_KEY, "worker-" + id);
        this.executionId = MDC.putCloseable("workerExecutionId", id);
        this.taskId = MDC.putCloseable("taskId", taskIdValue);
    }

    public static WorkerTraceContext open(Long taskId) {
        return new WorkerTraceContext(String.valueOf(taskId));
    }

    @Override
    public void close() {
        taskId.close();
        executionId.close();
        traceId.close();
    }
}
