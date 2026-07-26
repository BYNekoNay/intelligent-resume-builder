package com.intelligentresume.export.service;

import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.common.observability.PdfFailureCategory;
import com.intelligentresume.common.observability.WorkerTraceContext;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Polls and renders PDF export tasks after atomically claiming a short lease. */
@Component
public class ExportTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskWorker.class);

    private final ResumeVersionRepository resumeVersionRepository;
    private final PdfServiceClient pdfServiceClient;
    private final ExportStorageService storageService;
    private final ExportTaskLeaseService leaseService;
    private final int batchSize;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;

    public ExportTaskWorker(ResumeVersionRepository resumeVersionRepository,
                            PdfServiceClient pdfServiceClient,
                            ExportStorageService storageService,
                            ExportTaskLeaseService leaseService,
                            @Value("${app.pdf.worker.batch-size:3}") int batchSize,
                            AppObservability observability,
                            FailureCategoryClassifier failureCategoryClassifier) {
        this.resumeVersionRepository = resumeVersionRepository;
        this.pdfServiceClient = pdfServiceClient;
        this.storageService = storageService;
        this.leaseService = leaseService;
        this.batchSize = batchSize;
        this.observability = observability;
        this.failureCategoryClassifier = failureCategoryClassifier;
    }

    @Scheduled(fixedDelayString = "${app.pdf.worker.poll-interval-ms:3000}")
    public void poll() {
        try {
            List<ExportTask> batch = leaseService.claimBatch("pdf-" + UUID.randomUUID(), batchSize);
            for (ExportTask task : batch) {
                processTask(task);
            }
        } catch (Exception e) {
            log.error("Error in export task worker poll cycle", e);
        }
    }

    private void processTask(ExportTask task) {
        long startedAt = System.nanoTime();
        try (WorkerTraceContext ignored = WorkerTraceContext.open(task.getId())) {
            try {
                ResumeVersion version = resumeVersionRepository.findById(task.getResumeVersionId()).orElse(null);
                if (version == null || version.getDeletedAt() != null) {
                    markFailed(task, "Resume version is unavailable");
                    return;
                }

                Map<String, Object> payload = version.getResumeJson();
                if (payload == null || payload.isEmpty()) {
                    markFailed(task, "Resume content is empty");
                    return;
                }

                byte[] pdfBytes = pdfServiceClient.render(task.getTemplateCode(), payload);
                ExportStorageService.StoredFile stored = storageService.store(pdfBytes, "pdf");
                leaseService.releaseSuccess(task, stored);
                log.info("PDF export task completed: template={}, fileSizeBytes={}", task.getTemplateCode(), stored.size());
            } catch (Exception e) {
                PdfFailureCategory category = failureCategoryClassifier.pdf(e);
                log.warn("PDF export task failed: template={}, category={}, exception={}",
                        task.getTemplateCode(), category, e.getClass().getSimpleName());
                markFailed(task, e.getMessage());
            } finally {
                boolean success = task.getStatus() == ExportStatus.SUCCESS;
                PdfFailureCategory category = success ? PdfFailureCategory.NONE
                        : failureCategoryClassifier.pdfMessage(task.getErrorMessage());
                observability.recordPdfExport(task.getTemplateCode(), success, category,
                        Duration.ofNanos(System.nanoTime() - startedAt));
            }
        }
    }

    private void markFailed(ExportTask task, String errorMessage) {
        leaseService.releaseFailed(task, errorMessage);
    }
}
