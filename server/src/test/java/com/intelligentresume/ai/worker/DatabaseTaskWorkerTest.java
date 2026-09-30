package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry.Group;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseTaskWorkerTest {

    private final TaskLeaseService leaseService = mock(TaskLeaseService.class);
    private final TaskExecutionService executionService = mock(TaskExecutionService.class);

    private AiTaskWorkerProperties properties() {
        AiTaskWorkerProperties properties = new AiTaskWorkerProperties();
        properties.setBatchSize(5);
        properties.setHeavyConcurrency(1);
        properties.setLightConcurrency(1);
        return properties;
    }

    private List<AiTaskType> heavyTypes() {
        return AiTaskCapabilityRegistry.typesIn(Group.HEAVY);
    }

    private List<AiTaskType> lightTypes() {
        return AiTaskCapabilityRegistry.typesIn(Group.LIGHT);
    }

    @Test
    @DisplayName("每个分组各领取并执行一次")
    void dispatchesClaimsForEachGroup() {
        AiTask heavy = task(1L, AiTaskType.JOB_GENERATION);
        AiTask light = task(2L, AiTaskType.INLINE_OPTIMIZE);
        when(leaseService.claimBatch(anyString(), anyInt(), eq(heavyTypes()))).thenReturn(List.of(heavy));
        when(leaseService.claimBatch(anyString(), anyInt(), eq(lightTypes()))).thenReturn(List.of(light));

        new DatabaseTaskWorker(leaseService, executionService, properties(), Runnable::run, Runnable::run).poll();

        verify(executionService).execute(eq(heavy), anyString());
        verify(executionService).execute(eq(light), anyString());
    }

    @Test
    @DisplayName("在途任务占满额度后不再领取，任务完成后额度释放")
    void respectsInFlightBudgetAndReleasesSlotAfterCompletion() {
        when(leaseService.claimBatch(anyString(), anyInt(), eq(heavyTypes())))
                .thenReturn(List.of(task(1L, AiTaskType.JOB_GENERATION)));
        List<Runnable> pending = new ArrayList<>();
        Executor capturing = pending::add;
        DatabaseTaskWorker worker =
                new DatabaseTaskWorker(leaseService, executionService, properties(), capturing, capturing);

        worker.poll();
        worker.poll();
        // 额度 1 且任务仍在途 → 第二次 poll 不应再领取重任务
        verify(leaseService, times(1)).claimBatch(anyString(), anyInt(), eq(heavyTypes()));

        pending.forEach(Runnable::run);

        worker.poll();
        // 在途任务完成后额度释放 → 可再次领取
        verify(leaseService, times(2)).claimBatch(anyString(), anyInt(), eq(heavyTypes()));
    }

    @Test
    @DisplayName("重任务额度被占满时，轻任务仍照常领取（不被饿死）")
    void lightGroupIsClaimedEvenWhenHeavyBudgetIsFull() {
        when(leaseService.claimBatch(anyString(), anyInt(), eq(heavyTypes())))
                .thenReturn(List.of(task(1L, AiTaskType.JOB_GENERATION)));
        when(leaseService.claimBatch(anyString(), anyInt(), eq(lightTypes())))
                .thenReturn(List.of(task(2L, AiTaskType.INLINE_OPTIMIZE)));
        List<Runnable> heavyPending = new ArrayList<>();
        List<Runnable> lightPending = new ArrayList<>();
        DatabaseTaskWorker worker = new DatabaseTaskWorker(
                leaseService, executionService, properties(), heavyPending::add, lightPending::add);

        worker.poll();
        assertTrue(heavyPending.size() == 1, "重任务已进入在途，额度占满");

        // 让轻任务完成并释放其额度；重任务仍在途
        lightPending.forEach(Runnable::run);
        lightPending.clear();

        worker.poll();
        verify(leaseService, times(1)).claimBatch(anyString(), anyInt(), eq(heavyTypes()));
        verify(leaseService, times(2)).claimBatch(anyString(), anyInt(), eq(lightTypes()));
    }

    private AiTask task(Long id, AiTaskType type) {
        AiTask task = new AiTask();
        task.setId(id);
        task.setTaskType(type);
        task.setStatus(AiTaskStatus.RUNNING);
        task.setRetryCount(1);
        return task;
    }
}