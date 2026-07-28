package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskLeaseService 单元测试（Mockito）。
 * 覆盖:领取、竞争失败、可重试/不可重试失败、成功释放。
 */
@ExtendWith(MockitoExtension.class)
class TaskLeaseServiceTest {

    @Mock private AiTaskRepository taskRepository;
    @Mock private AiTaskWorkerProperties properties;

    private TaskLeaseService service;

    @BeforeEach
    void setUp() {
        service = new TaskLeaseService(taskRepository, properties);
    }

    @Test
    @DisplayName("领取 PENDING 任务 → RUNNING + retryCount 递增")
    void claimBatch_acquiresPendingTask() {
        when(properties.getLeaseSeconds()).thenReturn(60);
        AiTask task = task(1L, AiTaskStatus.PENDING, 0);
        when(taskRepository.claimableTasks(5)).thenReturn(List.of(task));
        when(taskRepository.acquireLease(eq(1L), anyString(), any())).thenReturn(1);

        List<AiTask> claimed = service.claimBatch("worker-1", 5);

        assertEquals(1, claimed.size());
        assertEquals(AiTaskStatus.RUNNING, claimed.get(0).getStatus());
        assertEquals("worker-1", claimed.get(0).getLeaseOwner());
        assertEquals(1, claimed.get(0).getRetryCount());
    }

    @Test
    @DisplayName("acquireLease 返回 0（被其他实例抢占）→ 跳过")
    void claimBatch_leaseAcquisitionFails_skipsTask() {
        when(properties.getLeaseSeconds()).thenReturn(60);
        AiTask task = task(1L, AiTaskStatus.PENDING, 0);
        when(taskRepository.claimableTasks(5)).thenReturn(List.of(task));
        when(taskRepository.acquireLease(eq(1L), anyString(), any())).thenReturn(0);

        List<AiTask> claimed = service.claimBatch("worker-1", 5);

        assertTrue(claimed.isEmpty());
    }

    @Test
    @DisplayName("可重试失败 + retryCount < maxRetries → 重置 PENDING")
    void releaseFailed_retryable_underMaxRetries_resetsPending() {
        when(properties.getMaxRetries()).thenReturn(3);
        AiTask task = task(1L, AiTaskStatus.RUNNING, 1);
        task.setLeaseOwner("worker-1");
        when(taskRepository.findRunningByIdAndOwnerForUpdate(1L, "worker-1")).thenReturn(java.util.Optional.of(task));

        service.releaseFailed(task, "worker-1", "timeout", true);

        assertEquals(AiTaskStatus.PENDING, task.getStatus());
        assertEquals("timeout", task.getErrorMessage());
        assertNull(task.getLeaseOwner());
        assertNull(task.getLeaseExpiresAt());
        verify(taskRepository).save(task);
    }

    @Test
    @DisplayName("可重试失败 + retryCount >= maxRetries → FAILED")
    void releaseFailed_retryable_overMaxRetries_marksFailed() {
        when(properties.getMaxRetries()).thenReturn(3);
        AiTask task = task(1L, AiTaskStatus.RUNNING, 3);
        task.setLeaseOwner("worker-1");
        when(taskRepository.findRunningByIdAndOwnerForUpdate(1L, "worker-1")).thenReturn(java.util.Optional.of(task));

        service.releaseFailed(task, "worker-1", "timeout", true);

        assertEquals(AiTaskStatus.FAILED, task.getStatus());
    }

    @Test
    @DisplayName("不可重试失败 → 直接 FAILED")
    void releaseFailed_notRetryable_marksFailed() {
        AiTask task = task(1L, AiTaskStatus.RUNNING, 1);
        task.setLeaseOwner("worker-1");
        when(taskRepository.findRunningByIdAndOwnerForUpdate(1L, "worker-1")).thenReturn(java.util.Optional.of(task));

        service.releaseFailed(task, "worker-1", "invalid input", false);

        assertEquals(AiTaskStatus.FAILED, task.getStatus());
        assertEquals("invalid input", task.getErrorMessage());
    }

    @Test
    @DisplayName("成功释放 → SUCCESS + 结果写入 + 租约清除")
    void releaseSuccess_setsSuccessAndClearsLease() {
        AiTask task = task(1L, AiTaskStatus.RUNNING, 1);
        task.setLeaseOwner("worker-1");
        task.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(60));

        Map<String, Object> result = Map.of("output", "generated content");
        when(taskRepository.findRunningByIdAndOwnerForUpdate(1L, "worker-1")).thenReturn(java.util.Optional.of(task));
        service.releaseSuccess(task, "worker-1", result);

        assertEquals(AiTaskStatus.SUCCESS, task.getStatus());
        assertEquals(result, task.getResultJson());
        assertNull(task.getLeaseOwner());
        assertNull(task.getLeaseExpiresAt());
        assertNull(task.getErrorMessage());
        verify(taskRepository).save(task);
    }

    @Test
    void staleOwnerCannotRenewOrCompleteTask() {
        AiTask task = task(1L, AiTaskStatus.RUNNING, 2);
        task.setLeaseOwner("worker-2");
        when(properties.getLeaseSeconds()).thenReturn(60);
        when(taskRepository.renewLease(eq(1L), eq("worker-1"), any())).thenReturn(0);
        when(taskRepository.findRunningByIdAndOwnerForUpdate(1L, "worker-1")).thenReturn(java.util.Optional.empty());

        assertFalse(service.renew(1L, "worker-1"));
        assertFalse(service.releaseSuccess(task, "worker-1", Map.of("output", "stale")));
        assertEquals(AiTaskStatus.RUNNING, task.getStatus());
        assertEquals("worker-2", task.getLeaseOwner());
        verify(taskRepository, never()).save(any());
    }

    private AiTask task(Long id, AiTaskStatus status, int retryCount) {
        AiTask t = new AiTask();
        t.setId(id);
        t.setUserId(100L);
        t.setTaskType(AiTaskType.JOB_GENERATION);
        t.setIdempotencyKey("key-" + id);
        t.setRequestFingerprint("fp-" + id);
        t.setInputSnapshotJson(Map.of("taskType", "JOB_GENERATION"));
        t.setStatus(status);
        t.setRetryCount(retryCount);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }
}
