package com.intelligentresume.common.observability;

import com.intelligentresume.common.api.TraceIdFilter;
import org.slf4j.MDC;

import java.util.UUID;

/** Creates a fresh trace for an asynchronous execution and clears MDC afterwards. */
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
