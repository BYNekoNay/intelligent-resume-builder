package com.intelligentresume.export.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
}
