package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportExpiryServiceTest {

    @Test
    void deletesBytesAndClearsStorageMetadataBeforeMarkingExpired() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportStorageService storage = mock(ExportStorageService.class);
        ExportTask task = expiredSuccessfulTask();
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(storage.delete("secret.pdf")).thenReturn(true);

        boolean expired = new ExportExpiryService(repository, storage, 100)
                .expireIfDue(7L, LocalDateTime.now());

        assertTrue(expired);
        assertEquals(ExportStatus.EXPIRED, task.getStatus());
        assertNull(task.getStorageKey());
        assertNull(task.getFileSizeBytes());
        assertNull(task.getSha256());
        verify(repository).save(task);
    }

    @Test
    void leavesTaskRetryableWhenPhysicalDeletionFails() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportStorageService storage = mock(ExportStorageService.class);
        ExportTask task = expiredSuccessfulTask();
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(storage.delete("secret.pdf")).thenReturn(false);

        boolean expired = new ExportExpiryService(repository, storage, 100)
                .expireIfDue(7L, LocalDateTime.now());

        assertFalse(expired);
        assertEquals(ExportStatus.SUCCESS, task.getStatus());
        assertEquals("secret.pdf", task.getStorageKey());
        verify(repository, never()).save(any());
    }

    @Test
    void scheduledCleanupProcessesUnvisitedExpiredTasks() {
        ExportTaskRepository repository = mock(ExportTaskRepository.class);
        ExportStorageService storage = mock(ExportStorageService.class);
        ExportTask task = expiredSuccessfulTask();
        when(repository.findExpiredSuccessfulForUpdate(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(task));
        when(storage.delete("secret.pdf")).thenReturn(true);

        new ExportExpiryService(repository, storage, 25).cleanupExpired();

        assertEquals(ExportStatus.EXPIRED, task.getStatus());
        verify(repository).findExpiredSuccessfulForUpdate(any(LocalDateTime.class), argThat(page -> page.getPageSize() == 25));
        verify(repository).save(task);
    }

    private ExportTask expiredSuccessfulTask() {
        ExportTask task = new ExportTask();
        task.setId(7L);
        task.setStatus(ExportStatus.SUCCESS);
        task.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        task.setStorageKey("secret.pdf");
        task.setFileSizeBytes(123L);
        task.setSha256("a".repeat(64));
        return task;
    }
}
