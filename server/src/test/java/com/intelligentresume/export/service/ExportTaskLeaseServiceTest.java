package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportTaskLeaseServiceTest {

    @Test
    void claimsOnlyTasksWhoseAtomicUpdateSucceeds() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask first = task(1L);
        ExportTask second = task(2L);
        when(repository.claimableTasks(3)).thenReturn(List.of(first, second));
        when(repository.acquireLease(eq(1L), eq("worker-a"), any(LocalDateTime.class))).thenReturn(1);
        when(repository.acquireLease(eq(2L), eq("worker-a"), any(LocalDateTime.class))).thenReturn(0);

        List<ExportTask> claimed = new ExportTaskLeaseService(repository, 90).claimBatch("worker-a", 3);

        assertEquals(List.of(first), claimed);
        assertEquals(ExportStatus.RUNNING, first.getStatus());
        assertEquals("worker-a", first.getLeaseOwner());
        assertEquals(1, first.getRetryCount());
    }

    @Test
    void completesAndClearsTheLease() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask task = task(1L);
        task.setLeaseOwner("worker-a");
        task.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(repository.findRunningByIdAndOwnerForUpdate(1L, "worker-a")).thenReturn(Optional.of(task));
        ExportStorageService.StoredFile stored = new ExportStorageService.StoredFile("random.pdf", 42L, "checksum");

        boolean released = new ExportTaskLeaseService(repository, 90).releaseSuccess(task, stored);

        assertTrue(released);
        assertEquals(ExportStatus.SUCCESS, task.getStatus());
        assertEquals("random.pdf", task.getStorageKey());
        assertEquals(42L, task.getFileSizeBytes());
        assertEquals("checksum", task.getSha256());
        assertNull(task.getLeaseOwner());
        assertNull(task.getLeaseExpiresAt());
        verify(repository).save(task);
    }

    @Test
    void truncatesFailureAndClearsTheLease() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask task = task(1L);
        task.setLeaseOwner("worker-a");
        when(repository.findRunningByIdAndOwnerForUpdate(1L, "worker-a")).thenReturn(Optional.of(task));

        boolean released = new ExportTaskLeaseService(repository, 90).releaseFailed(task, "x".repeat(1100));

        assertTrue(released);
        assertEquals(ExportStatus.FAILED, task.getStatus());
        assertEquals(1000, task.getErrorMessage().length());
        assertNull(task.getLeaseOwner());
        verify(repository).save(task);
    }

    @Test
    void discardsStaleSuccessWhenLeaseWasTakenOver() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask task = task(1L);
        task.setLeaseOwner("worker-a");
        // 租约已被其他实例接管：数据库已不存在 owner 的 RUNNING 任务
        when(repository.findRunningByIdAndOwnerForUpdate(1L, "worker-a")).thenReturn(Optional.empty());
        ExportStorageService.StoredFile stored = new ExportStorageService.StoredFile("random.pdf", 42L, "checksum");

        boolean released = new ExportTaskLeaseService(repository, 90).releaseSuccess(task, stored);

        assertFalse(released);
        // 实体未被修改：仍保持默认 PENDING（未置 SUCCESS），leaseOwner 原样保留
        assertEquals(ExportStatus.PENDING, task.getStatus());
        assertEquals("worker-a", task.getLeaseOwner());
        verify(repository, never()).save(task);
    }

    @Test
    void discardsStaleFailureWhenLeaseWasTakenOver() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask task = task(1L);
        task.setLeaseOwner("worker-a");
        when(repository.findRunningByIdAndOwnerForUpdate(1L, "worker-a")).thenReturn(Optional.empty());

        boolean released = new ExportTaskLeaseService(repository, 90).releaseFailed(task, "boom");

        assertFalse(released);
        // 实体未被修改：仍保持默认 PENDING（未置 FAILED），leaseOwner 原样保留
        assertEquals(ExportStatus.PENDING, task.getStatus());
        assertEquals("worker-a", task.getLeaseOwner());
        verify(repository, never()).save(task);
    }

    private ExportTask task(Long id) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setRetryCount(0);
        return task;
    }
}
