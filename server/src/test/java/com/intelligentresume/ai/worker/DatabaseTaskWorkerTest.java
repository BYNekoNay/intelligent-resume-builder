package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseTaskWorkerTest {

    @Test
    void claimsEachTaskImmediatelyBeforeExecution() {
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        TaskExecutionService executionService = mock(TaskExecutionService.class);
        AiTaskWorkerProperties properties = new AiTaskWorkerProperties();
        properties.setBatchSize(3);
        AiTask first = new AiTask();
        first.setId(1L);
        AiTask second = new AiTask();
        second.setId(2L);
        when(leaseService.claimBatch(anyString(), eq(1)))
                .thenReturn(List.of(first), List.of(second), List.of());

        new DatabaseTaskWorker(leaseService, executionService, properties).poll();

        InOrder order = inOrder(leaseService, executionService);
        order.verify(leaseService).claimBatch(anyString(), eq(1));
        order.verify(executionService).execute(eq(first), anyString());
        order.verify(leaseService).claimBatch(anyString(), eq(1));
        order.verify(executionService).execute(eq(second), anyString());
        order.verify(leaseService).claimBatch(anyString(), eq(1));
    }
}
