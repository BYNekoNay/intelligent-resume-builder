package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        ExportStorageService.StoredFile stored = new ExportStorageService.StoredFile("random.pdf", 42L, "checksum");

        new ExportTaskLeaseService(repository, 90).releaseSuccess(task, stored);

        assertEquals(ExportStatus.SUCCESS, task.getStatus());
        assertEquals("random.pdf", task.getStorageKey());
        assertNull(task.getLeaseOwner());
        assertNull(task.getLeaseExpiresAt());
        verify(repository).save(task);
    }

    @Test
    void truncatesFailureAndClearsTheLease() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportTask task = task(1L);
        task.setLeaseOwner("worker-a");

        new ExportTaskLeaseService(repository, 90).releaseFailed(task, "x".repeat(1100));

        assertEquals(ExportStatus.FAILED, task.getStatus());
        assertEquals(1000, task.getErrorMessage().length());
        assertNull(task.getLeaseOwner());
        verify(repository).save(task);
    }

    private ExportTask task(Long id) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setRetryCount(0);
        return task;
    }
}
