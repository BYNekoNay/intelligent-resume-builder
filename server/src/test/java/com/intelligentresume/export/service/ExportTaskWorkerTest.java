package com.intelligentresume.export.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExportTaskWorkerTest {

    @Test
    void doesNotRenderQueuedTaskForDisabledAccount() {
        ResumeVersionRepository versionRepository = mock(ResumeVersionRepository.class);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        ExportStorageService storageService = mock(ExportStorageService.class);
        ExportTaskLeaseService leaseService = mock(ExportTaskLeaseService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppObservability observability = mock(AppObservability.class);
        FailureCategoryClassifier classifier = new FailureCategoryClassifier();

        ExportTask task = new ExportTask();
        task.setId(8L);
        task.setUserId(100L);
        task.setResumeVersionId(20L);
        task.setTemplateCode("classic");
        task.setLeaseOwner("pdf-worker");
        User disabled = new User();
        disabled.setId(100L);
        disabled.setStatus(User.UserStatus.DISABLED);
        when(leaseService.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(userRepository.findById(100L)).thenReturn(Optional.of(disabled));

        ExportTaskWorker worker = new ExportTaskWorker(
                versionRepository, userRepository, pdfServiceClient, storageService, leaseService,
                3, observability, classifier);

        worker.poll();

        verify(leaseService).releaseFailed(eq(task), contains("disabled"));
        verifyNoInteractions(versionRepository, pdfServiceClient, storageService);
    }

    @Test
    void deletesOrphanedFileWhenLeaseWasTakenOver() {
        // #30：租约被接管时结果被丢弃，本次渲染出的文件必须立即清理，不能留成孤儿。
        ResumeVersionRepository versionRepository = mock(ResumeVersionRepository.class);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        ExportStorageService storageService = mock(ExportStorageService.class);
        ExportTaskLeaseService leaseService = mock(ExportTaskLeaseService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppObservability observability = mock(AppObservability.class);
        FailureCategoryClassifier classifier = new FailureCategoryClassifier();

        ExportTask task = new ExportTask();
        task.setId(9L);
        task.setUserId(100L);
        task.setResumeVersionId(20L);
        task.setTemplateCode("classic");
        task.setLeaseOwner("pdf-worker");
        task.setStatus(ExportStatus.RUNNING);
        User active = new User();
        active.setId(100L);
        active.setStatus(User.UserStatus.ACTIVE);
        ResumeVersion version = new ResumeVersion();
        version.setId(20L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Candidate")));
        when(leaseService.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(userRepository.findById(100L)).thenReturn(Optional.of(active));
        when(versionRepository.findById(20L)).thenReturn(Optional.of(version));
        when(pdfServiceClient.render(eq("classic"), any())).thenReturn(new byte[]{1, 2, 3});
        ExportStorageService.StoredFile stored = new ExportStorageService.StoredFile("orphan.pdf", 3L, "sha");
        when(storageService.store(any(), anyString())).thenReturn(stored);
        when(leaseService.releaseSuccess(eq(task), eq(stored))).thenReturn(false);

        ExportTaskWorker worker = new ExportTaskWorker(
                versionRepository, userRepository, pdfServiceClient, storageService, leaseService,
                3, observability, classifier);

        worker.poll();

        verify(storageService).delete("orphan.pdf");
        assertEquals(ExportStatus.RUNNING, task.getStatus(), "陈旧结果不得把任务改写为 SUCCESS");
    }

    @Test
    void keepsRenderedFileWhenLeaseStillHolds() {
        ResumeVersionRepository versionRepository = mock(ResumeVersionRepository.class);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        ExportStorageService storageService = mock(ExportStorageService.class);
        ExportTaskLeaseService leaseService = mock(ExportTaskLeaseService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppObservability observability = mock(AppObservability.class);

        ExportTask task = new ExportTask();
        task.setId(10L);
        task.setUserId(100L);
        task.setResumeVersionId(20L);
        task.setTemplateCode("classic");
        task.setLeaseOwner("pdf-worker");
        User active = new User();
        active.setId(100L);
        active.setStatus(User.UserStatus.ACTIVE);
        ResumeVersion version = new ResumeVersion();
        version.setId(20L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Candidate")));
        when(leaseService.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(userRepository.findById(100L)).thenReturn(Optional.of(active));
        when(versionRepository.findById(20L)).thenReturn(Optional.of(version));
        when(pdfServiceClient.render(eq("classic"), any())).thenReturn(new byte[]{1, 2, 3});
        ExportStorageService.StoredFile stored = new ExportStorageService.StoredFile("kept.pdf", 3L, "sha");
        when(storageService.store(any(), anyString())).thenReturn(stored);
        when(leaseService.releaseSuccess(eq(task), eq(stored))).thenReturn(true);

        ExportTaskWorker worker = new ExportTaskWorker(
                versionRepository, userRepository, pdfServiceClient, storageService, leaseService,
                3, observability, new FailureCategoryClassifier());

        worker.poll();

        verify(storageService, never()).delete(anyString());
    }
}
