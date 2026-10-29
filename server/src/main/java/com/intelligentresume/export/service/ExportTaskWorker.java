package com.intelligentresume.export.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final PdfServiceClient pdfServiceClient;
    private final com.intelligentresume.export.pdf.JavaResumePdfRenderer javaResumePdfRenderer;
    private final com.intelligentresume.config.DesktopModeProperties desktopModeProperties;
    private final ExportStorageService storageService;
    private final ExportTaskLeaseService leaseService;
    private final int batchSize;
    private final int maxAttempts;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;

    public ExportTaskWorker(ResumeVersionRepository resumeVersionRepository,
                            UserRepository userRepository,
                            PdfServiceClient pdfServiceClient,
                            com.intelligentresume.export.pdf.JavaResumePdfRenderer javaResumePdfRenderer,
                            com.intelligentresume.config.DesktopModeProperties desktopModeProperties,
                            ExportStorageService storageService,
                            ExportTaskLeaseService leaseService,
                            @Value("${app.pdf.worker.batch-size:1}") int batchSize,
                            @Value("${app.pdf.worker.max-attempts:3}") int maxAttempts,
                            AppObservability observability,
                            FailureCategoryClassifier failureCategoryClassifier) {
        this.resumeVersionRepository = resumeVersionRepository;
        this.userRepository = userRepository;
        this.pdfServiceClient = pdfServiceClient;
        this.javaResumePdfRenderer = javaResumePdfRenderer;
        this.desktopModeProperties = desktopModeProperties;
        this.storageService = storageService;
        this.leaseService = leaseService;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
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
                User user = userRepository.findById(task.getUserId()).orElse(null);
                if (user == null || user.getStatus() != User.UserStatus.ACTIVE || user.getDeletedAt() != null) {
                    markFailed(task, "Account is disabled");
                    return;
                }

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

                // 桌面本地工作区（ADR-012 D4）：不依赖 Node + Chromium，改用纯 Java 渲染器，
                // 这样安装包不必带约 292MB 的 Chromium 与 Node 运行时。
                // 在线版继续走 pdf-service（Chrome 打印），两条路径各自有回归。
                byte[] pdfBytes = desktopModeProperties.isEnabled()
                        ? javaResumePdfRenderer.render(task.getTemplateCode(), payload)
                        : pdfServiceClient.render(task.getTemplateCode(), payload);
                ExportStorageService.StoredFile stored = storageService.store(pdfBytes, "pdf");
                if (!leaseService.releaseSuccess(task, stored)) {
                    // #30：租约已被接管时结果被丢弃，本 worker 渲染出的文件没有任何任务引用，
                    // 立即清理，否则过期清理作业不会看到它（只清理 SUCCESS 行的文件）。
                    if (!storageService.delete(stored.storageKey())) {
                        log.warn("Failed to delete orphaned export file for task {}", task.getId());
                    }
                    return;
                }
                log.info("PDF export task completed: template={}, fileSizeBytes={}", task.getTemplateCode(), stored.size());
            } catch (PdfCapacityException capacity) {
                // 容量拒绝不是「这次渲染坏了」，而是「现在没名额」：pdf-service 已经用
                // 503 + Retry-After 说明等多久，这里按它给的时间延迟重排。
                // 只有真的试到上限才判失败 —— 之前是第一次就 FAILED，用户看到
                // 「请稍后重试」却必须自己回页面点，服务端给的等待时长完全没被使用。
                if (task.getRetryCount() < maxAttempts) {
                    leaseService.releaseForRetry(task, capacity.getMessage(), capacity.getRetryAfterSeconds());
                    log.info("PDF export task requeued after capacity rejection: taskId={}, attempt={}/{}, retryAfterSeconds={}",
                            task.getId(), task.getRetryCount(), maxAttempts, capacity.getRetryAfterSeconds());
                } else {
                    markFailed(task, capacity.getMessage() + "（已尝试 " + task.getRetryCount() + " 次）");
                }
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
