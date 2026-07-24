package com.intelligentresume.export.service;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 导出任务工作器。定期轮询 PENDING 任务,调用 PDF 服务渲染并存储。
 *
 * <p>轮询间隔由 {@code app.pdf.worker.poll-interval-ms} 控制。
 * 测试环境设为 60000ms 以避免干扰集成测试。
 */
@Component
public class ExportTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskWorker.class);

    private final ExportTaskRepository exportTaskRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final PdfServiceClient pdfServiceClient;
    private final ExportStorageService storageService;
    private final int batchSize;

    public ExportTaskWorker(ExportTaskRepository exportTaskRepository,
                            ResumeVersionRepository resumeVersionRepository,
                            PdfServiceClient pdfServiceClient,
                            ExportStorageService storageService,
                            @Value("${app.pdf.worker.batch-size:3}") int batchSize) {
        this.exportTaskRepository = exportTaskRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.pdfServiceClient = pdfServiceClient;
        this.storageService = storageService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.pdf.worker.poll-interval-ms:3000}")
    public void poll() {
        try {
            // 领取 PENDING 任务(单实例 MVP 无需悲观锁)
            List<ExportTask> tasks = exportTaskRepository.findByStatus(ExportStatus.PENDING);
            if (tasks.isEmpty()) {
                return;
            }
            // 只取 batchSize 个
            List<ExportTask> batch = tasks.subList(0, Math.min(batchSize, tasks.size()));
            for (ExportTask task : batch) {
                task.setStatus(ExportStatus.RUNNING);
            }
            exportTaskRepository.saveAll(batch);

            for (ExportTask task : batch) {
                processTask(task);
            }
        } catch (Exception e) {
            log.error("Error in export task worker poll cycle", e);
        }
    }

    private void processTask(ExportTask task) {
        try {
            // 加载简历版本的 resumeJson 作为 payload
            ResumeVersion version = resumeVersionRepository.findById(task.getResumeVersionId())
                    .orElse(null);
            if (version == null || version.getDeletedAt() != null) {
                markFailed(task, "简历版本不存在或已删除");
                return;
            }

            Map<String, Object> payload = version.getResumeJson();
            if (payload == null || payload.isEmpty()) {
                markFailed(task, "简历内容为空");
                return;
            }

            // 调用 PDF 服务
            byte[] pdfBytes = pdfServiceClient.render(task.getTemplateCode(), payload);

            // 存储
            ExportStorageService.StoredFile stored = storageService.store(pdfBytes, "pdf");

            // 更新任务
            task.setStatus(ExportStatus.SUCCESS);
            task.setStorageKey(stored.storageKey());
            task.setFileSizeBytes(stored.size());
            task.setSha256(stored.checksumSha256());
            task.setErrorMessage(null);
            exportTaskRepository.save(task);

            log.debug("Export task {} completed: {} bytes, sha256={}", task.getId(), stored.size(), stored.checksumSha256());

        } catch (Exception e) {
            log.error("Export task {} failed: {}", task.getId(), e.getMessage());
            markFailed(task, e.getMessage());
        }
    }

    private void markFailed(ExportTask task, String errorMessage) {
        task.setStatus(ExportStatus.FAILED);
        task.setErrorMessage(errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000) : errorMessage);
        exportTaskRepository.save(task);
    }
}
