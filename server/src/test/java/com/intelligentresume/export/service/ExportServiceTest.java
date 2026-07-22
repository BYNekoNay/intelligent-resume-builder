package com.intelligentresume.export.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.export.client.PdfServiceClient;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.dto.ExportRequest;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private ExportTaskRepository exportTaskRepository;
    @Mock private PdfServiceClient pdfServiceClient;
    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumeVersionRepository resumeVersionRepository;

    @TempDir Path outputDir;
    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(exportTaskRepository, pdfServiceClient, resumeRepository,
                resumeVersionRepository, outputDir.toString(), 1, "classic,modern,minimal");
    }

    @Test
    void createsPendingTaskWithoutCallingThePdfService() {
        ResumeVersion version = new ResumeVersion();
        version.setId(10L);
        version.setResumeId(20L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Test User")));
        Resume resume = new Resume();
        resume.setId(20L);
        when(resumeVersionRepository.findById(10L)).thenReturn(Optional.of(version));
        when(resumeRepository.findByIdAndUserId(20L, 30L)).thenReturn(Optional.of(resume));
        when(exportTaskRepository.save(any(ExportTask.class))).thenAnswer(invocation -> {
            ExportTask task = invocation.getArgument(0);
            task.setId(40L);
            return task;
        });

        var response = exportService.createExport(new ExportRequest(10L, "classic"), 30L);

        assertThat(response.id()).isEqualTo(40L);
        assertThat(response.status()).isEqualTo(ExportTask.ExportStatus.PENDING);
        verifyNoInteractions(pdfServiceClient);
        ArgumentCaptor<ExportTask> saved = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskRepository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void acceptsModernTemplateForExportTask() {
        ResumeVersion version = new ResumeVersion();
        version.setId(11L);
        version.setResumeId(21L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Test User")));
        Resume resume = new Resume();
        resume.setId(21L);
        when(resumeVersionRepository.findById(11L)).thenReturn(Optional.of(version));
        when(resumeRepository.findByIdAndUserId(21L, 30L)).thenReturn(Optional.of(resume));
        when(exportTaskRepository.save(any(ExportTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = exportService.createExport(new ExportRequest(11L, "modern"), 30L);

        assertThat(response.templateCode()).isEqualTo("modern");
        verifyNoInteractions(pdfServiceClient);
    }

    @Test
    void workerRendersPendingTaskAndStoresThePdf() throws Exception {
        ResumeVersion version = new ResumeVersion();
        version.setId(10L);
        version.setResumeJson(Map.of("basics", Map.of("name", "Test User")));
        ExportTask task = new ExportTask();
        task.setId(40L);
        task.setResumeVersionId(10L);
        task.setTemplateCode("classic");
        task.setStatus(ExportTask.ExportStatus.PENDING);
        byte[] pdf = "%PDF-test".getBytes();
        when(exportTaskRepository.findTop10ByStatusOrderByCreatedAtAsc(ExportTask.ExportStatus.PENDING))
                .thenReturn(List.of(task));
        when(resumeVersionRepository.findById(10L)).thenReturn(Optional.of(version));
        when(pdfServiceClient.render(eq("classic"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(pdf);
        when(exportTaskRepository.save(any(ExportTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        exportService.processPendingExports();

        assertThat(task.getStatus()).isEqualTo(ExportTask.ExportStatus.SUCCESS);
        assertThat(task.getSha256()).hasSize(64);
        assertThat(Files.readAllBytes(outputDir.resolve(task.getStorageKey() + ".pdf"))).isEqualTo(pdf);
    }

    @Test
    void serializesTaskIdForDocumentedExportContract() throws Exception {
        var response = new com.intelligentresume.export.dto.ExportTaskResponse(
                40L, 10L, "classic", ExportTask.ExportStatus.PENDING,
                null, null, null, null, null, null);

        assertThat(new ObjectMapper().valueToTree(response).path("taskId").asLong())
                .isEqualTo(40L);
    }

    @Test
    void retriesOnlyFailedExportsOwnedByTheCurrentUser() {
        ExportTask failed = new ExportTask();
        failed.setId(40L);
        failed.setUserId(30L);
        failed.setStatus(ExportTask.ExportStatus.FAILED);
        failed.setErrorMessage("renderer unavailable");
        when(exportTaskRepository.findByIdAndUserId(40L, 30L)).thenReturn(Optional.of(failed));
        when(exportTaskRepository.save(any(ExportTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = exportService.retry(40L, 30L);

        assertThat(response.status()).isEqualTo(ExportTask.ExportStatus.PENDING);
        assertThat(response.errorMessage()).isNull();
        assertThat(failed.getExpiresAt()).isNotNull();
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThatThrownBy(() -> exportService.retry(40L, 31L))
                .isInstanceOf(com.intelligentresume.common.error.BusinessException.class);
    }
}
