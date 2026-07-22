package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.export.client.PdfServiceClient;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.dto.ExportRequest;
import com.intelligentresume.export.dto.ExportTaskResponse;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportTaskRepository repository;
    private final PdfServiceClient pdfClient;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final String outputDir;
    private final long ttlHours;
    private final Set<String> allowedTemplates;

    public ExportService(ExportTaskRepository repository,
                         PdfServiceClient pdfClient,
                         ResumeRepository resumeRepository,
                         ResumeVersionRepository resumeVersionRepository,
                         @Value("${app.pdf.output-dir}") String outputDir,
                         @Value("${app.pdf.file-ttl-hours}") long ttlHours,
                         @Value("${app.pdf.allowed-template-codes}") String allowedTemplatesCsv) {
        this.repository = repository;
        this.pdfClient = pdfClient;
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.outputDir = outputDir;
        this.ttlHours = ttlHours;
        this.allowedTemplates = Set.of(allowedTemplatesCsv.split("\\s*,\\s*"));
    }

    @Transactional
    public ExportTaskResponse createExport(ExportRequest request, Long userId) {
        if (!allowedTemplates.contains(request.templateCode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "Unsupported template");
        }
        ResumeVersion version = resumeVersionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setResumeVersionId(version.getId());
        task.setTemplateCode(request.templateCode());
        task.setStatus(ExportTask.ExportStatus.PENDING);
        task.setExpiresAt(LocalDateTime.now().plusHours(ttlHours));
        return toResponse(repository.save(task));
    }

    @Transactional
    public void processPendingExports() {
        for (ExportTask task : repository.findTop10ByStatusOrderByCreatedAtAsc(ExportTask.ExportStatus.PENDING)) {
            render(task);
        }
    }

    private void render(ExportTask task) {
        task.setStatus(ExportTask.ExportStatus.RUNNING);
        repository.save(task);
        try {
            ResumeVersion version = resumeVersionRepository.findById(task.getResumeVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resumeVersionId", version.getId());
            payload.put("templateCode", task.getTemplateCode());
            payload.put("resumeJson", version.getResumeJson());
            byte[] pdf = pdfClient.render(task.getTemplateCode(), payload);

            String key = randomStorageKey();
            Path target = Paths.get(outputDir, key + ".pdf");
            Files.createDirectories(target.getParent());
            Files.write(target, pdf);

            task.setStorageKey(key);
            task.setFileSizeBytes((long) pdf.length);
            task.setSha256(sha256Hex(pdf));
            task.setStatus(ExportTask.ExportStatus.SUCCESS);
            repository.save(task);
        } catch (Exception exception) {
            log.warn("PDF export task {} failed", task.getId(), exception);
            task.setStatus(ExportTask.ExportStatus.FAILED);
            task.setErrorMessage(truncate(exception.getMessage()));
            repository.save(task);
        }
    }

    public ExportTaskResponse get(Long id, Long userId) {
        ExportTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(task);
    }

    @Transactional
    public ExportTaskResponse retry(Long id, Long userId) {
        ExportTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (task.getStatus() != ExportTask.ExportStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only failed exports can be retried");
        }
        task.setStatus(ExportTask.ExportStatus.PENDING);
        task.setErrorMessage(null);
        task.setExpiresAt(LocalDateTime.now().plusHours(ttlHours));
        task.setRetryCount(task.getRetryCount() + 1);
        return toResponse(repository.save(task));
    }

    public byte[] download(Long id, Long userId) {
        ExportTask task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (task.getStatus() != ExportTask.ExportStatus.SUCCESS
                || task.getExpiresAt() == null
                || task.getExpiresAt().isBefore(LocalDateTime.now())
                || task.getStorageKey() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        try {
            return Files.readAllBytes(Paths.get(outputDir, task.getStorageKey() + ".pdf"));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    @Transactional
    public void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<ExportTask> expired = repository.findAll().stream()
                .filter(task -> task.getStatus() == ExportTask.ExportStatus.SUCCESS)
                .filter(task -> task.getExpiresAt() != null && task.getExpiresAt().isBefore(now))
                .toList();
        for (ExportTask task : expired) {
            if (task.getStorageKey() != null) {
                try {
                    Files.deleteIfExists(Paths.get(outputDir, task.getStorageKey() + ".pdf"));
                } catch (IOException ignored) {
                    // The status still expires even when a stale local file cannot be removed.
                }
            }
            task.setStatus(ExportTask.ExportStatus.EXPIRED);
        }
        repository.saveAll(expired);
    }

    public String randomStorageKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private ExportTaskResponse toResponse(ExportTask task) {
        return new ExportTaskResponse(task.getId(), task.getResumeVersionId(), task.getTemplateCode(), task.getStatus(),
                task.getFileSizeBytes(), task.getSha256(), task.getErrorMessage(), task.getExpiresAt(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
