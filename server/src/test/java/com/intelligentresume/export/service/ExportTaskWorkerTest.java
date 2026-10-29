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
                versionRepository, userRepository, pdfServiceClient, new com.intelligentresume.export.pdf.JavaResumePdfRenderer(), desktopProperties(),
                storageService, leaseService,
                3, 3, observability, classifier);

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
                versionRepository, userRepository, pdfServiceClient, new com.intelligentresume.export.pdf.JavaResumePdfRenderer(), desktopProperties(),
                storageService, leaseService,
                3, 3, observability, classifier);

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
                versionRepository, userRepository, pdfServiceClient, new com.intelligentresume.export.pdf.JavaResumePdfRenderer(), desktopProperties(),
                storageService, leaseService,
                3, 3, observability, new FailureCategoryClassifier());

        worker.poll();

        verify(storageService, never()).delete(anyString());
    }

    /**
     * 容量拒绝（503 + Retry-After）**不是**任务失败：必须按对端给的等待时长重排。
     *
     * <p>回归的缺陷：此前 503 被压平成普通 PDF_FAILURE 并当场 releaseFailed —— 用户看到
     * 「请稍后重试」却必须自己回页面点按钮，而 pdf-service 已经把「等多久」写在响应头里。
     */
    @Test
    void requeuesTaskWhenPdfServiceRejectsForCapacity() {
        ResumeVersionRepository versionRepository = mock(ResumeVersionRepository.class);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        ExportStorageService storageService = mock(ExportStorageService.class);
        ExportTaskLeaseService leaseService = mock(ExportTaskLeaseService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppObservability observability = mock(AppObservability.class);

        ExportTask task = runningTask(11L, 1);
        givenActiveUserAndVersion(userRepository, versionRepository, leaseService, task);
        when(pdfServiceClient.render(eq("classic"), any()))
                .thenThrow(new PdfCapacityException("PDF 服务繁忙，请稍后重试", 7));

        ExportTaskWorker worker = new ExportTaskWorker(
                versionRepository, userRepository, pdfServiceClient,
                new com.intelligentresume.export.pdf.JavaResumePdfRenderer(), desktopProperties(),
                storageService, leaseService,
                3, 3, observability, new FailureCategoryClassifier());

        worker.poll();

        verify(leaseService).releaseForRetry(task, "PDF 服务繁忙，请稍后重试", 7);
        verify(leaseService, never()).releaseFailed(any(), anyString());
        verify(storageService, never()).store(any(), anyString());
    }

    /**
     * 试到上限才判失败，且文案带上已试次数（用户据此判断该等运维还是重新发起）。
     */
    @Test
    void failsTaskAfterCapacityRetriesAreExhausted() {
        ResumeVersionRepository versionRepository = mock(ResumeVersionRepository.class);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        ExportStorageService storageService = mock(ExportStorageService.class);
        ExportTaskLeaseService leaseService = mock(ExportTaskLeaseService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AppObservability observability = mock(AppObservability.class);

        // retryCount 已到第 3 次（max-attempts=3）→ 本次不再重排
        ExportTask task = runningTask(12L, 3);
        givenActiveUserAndVersion(userRepository, versionRepository, leaseService, task);
        when(pdfServiceClient.render(eq("classic"), any()))
                .thenThrow(new PdfCapacityException("PDF 服务繁忙，请稍后重试", 2));

        ExportTaskWorker worker = new ExportTaskWorker(
                versionRepository, userRepository, pdfServiceClient,
                new com.intelligentresume.export.pdf.JavaResumePdfRenderer(), desktopProperties(),
                storageService, leaseService,
                3, 3, observability, new FailureCategoryClassifier());

        worker.poll();

        verify(leaseService, never()).releaseForRetry(any(), anyString(), anyInt());
        verify(leaseService).releaseFailed(eq(task), contains("已尝试 3 次"));
    }

    private ExportTask runningTask(long id, int retryCount) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setUserId(100L);
        task.setResumeVersionId(20L);
        task.setTemplateCode("classic");
        task.setLeaseOwner("pdf-worker");
        task.setStatus(ExportStatus.RUNNING);
        task.setRetryCount(retryCount);
        return task;
    }

    private void givenActiveUserAndVersion(UserRepository userRepository,
                                           ResumeVersionRepository versionRepository,
                                           ExportTaskLeaseService leaseService,
                                           ExportTask task) {
        User active = new User();
        active.setId(100L);
        active.setStatus(User.UserStatus.ACTIVE);
        ResumeVersion version = new ResumeVersion();
        version.setId(20L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Candidate")));
        when(leaseService.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(userRepository.findById(100L)).thenReturn(Optional.of(active));
        when(versionRepository.findById(20L)).thenReturn(Optional.of(version));
    }

    /**
     * 桌面模式关闭：测试要继续走 pdf-service 路径（在线版行为），而不是纯 Java 渲染器。
     */
    private com.intelligentresume.config.DesktopModeProperties desktopProperties() {
        com.intelligentresume.config.DesktopModeProperties properties =
                new com.intelligentresume.config.DesktopModeProperties();
        properties.setEnabled(false);
        return properties;
    }
}
