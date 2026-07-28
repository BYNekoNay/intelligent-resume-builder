package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.dto.CreateExportRequest;
import com.intelligentresume.export.dto.ExportTaskStatusResponse;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.resume.service.ResumeTemplateCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 导出服务。编排创建、查询、下载流程。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>templateCode 必须属于统一支持列表</li>
 *   <li>跨用户 → NOT_FOUND(不泄露存在性)</li>
 *   <li>下载必须 status=SUCCESS 且未过期</li>
 *   <li>不暴露 storageKey</li>
 * </ul>
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportTaskRepository exportTaskRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final ExportStorageService storageService;
    private final ExportExpiryService expiryService;
    private final long fileTtlHours;

    public ExportService(ExportTaskRepository exportTaskRepository,
                         ResumeVersionRepository resumeVersionRepository,
                         ExportStorageService storageService,
                         ExportExpiryService expiryService,
                         @Value("${app.pdf.file-ttl-hours:24}") long fileTtlHours) {
        this.exportTaskRepository = exportTaskRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.storageService = storageService;
        this.expiryService = expiryService;
        this.fileTtlHours = fileTtlHours;
    }

    /**
     * 创建导出任务。校验简历版本归属后创建 PENDING 任务,由 worker 异步渲染。
     */
    @Transactional
    public ExportTaskStatusResponse create(CreateExportRequest req, Long userId) {
        // 1. 校验 templateCode
        if (!ResumeTemplateCodes.SUPPORTED.contains(req.templateCode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板代码不受支持");
        }

        // 2. 校验简历版本归属(跨用户 → NOT_FOUND)
        ResumeVersion version = resumeVersionRepository.findById(req.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (!version.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        // 软删的版本不可导出
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }

        // 3. 创建任务
        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setResumeVersionId(req.resumeVersionId());
        task.setTemplateCode(req.templateCode());
        task.setStatus(ExportStatus.PENDING);
        task.setExpiresAt(LocalDateTime.now().plusHours(fileTtlHours));
        exportTaskRepository.save(task);

        log.debug("Export task created: id={}, userId={}, versionId={}", task.getId(), userId, req.resumeVersionId());
        return toResponse(task);
    }

    /**
     * 查询导出任务状态。
     */
    public ExportTaskStatusResponse get(Long taskId, Long userId) {
        ExportTask task = findForUser(taskId, userId);

        // 过期检测:SUCCESS 但 expires_at < now → EXPIRED
        if (task.getStatus() == ExportStatus.SUCCESS
                && task.getExpiresAt() != null
                && task.getExpiresAt().isBefore(LocalDateTime.now())) {
            expiryService.expireIfDue(task.getId(), LocalDateTime.now());
            task.setStatus(ExportStatus.EXPIRED);
            task.setStorageKey(null);
            task.setFileSizeBytes(null);
            task.setSha256(null);
        }

        return toResponse(task);
    }

    /**
     * 下载 PDF 文件。受控流:校验归属、状态、过期。
     */
    public Resource download(Long taskId, Long userId) {
        ExportTask task = findForUser(taskId, userId);

        // 必须 SUCCESS
        if (task.getStatus() != ExportStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不可用");
        }

        // 过期检测
        if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(LocalDateTime.now())) {
            expiryService.expireIfDue(task.getId(), LocalDateTime.now());
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期");
        }

        // 读取文件
        byte[] content = storageService.read(task.getStorageKey());
        if (content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在");
        }

        return new ByteArrayResource(content);
    }

    /**
     * 重试失败的导出任务。
     */
    @Transactional
    public ExportTaskStatusResponse retry(Long taskId, Long userId) {
        ExportTask task = findForUser(taskId, userId);
        if (task.getStatus() != ExportStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的任务可以重试");
        }
        task.setStatus(ExportStatus.PENDING);
        task.setErrorMessage(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setExpiresAt(LocalDateTime.now().plusHours(fileTtlHours));
        exportTaskRepository.save(task);
        return toResponse(task);
    }

    private ExportTask findForUser(Long taskId, Long userId) {
        return exportTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "导出任务不存在"));
    }

    private ExportTaskStatusResponse toResponse(ExportTask task) {
        String downloadUrl = task.getStatus() == ExportStatus.SUCCESS
                ? "/api/exports/files/" + task.getId()
                : null;
        return new ExportTaskStatusResponse(
                task.getId(),
                task.getStatus().name(),
                task.getTemplateCode(),
                task.getFileSizeBytes(),
                task.getSha256(),
                task.getExpiresAt(),
                task.getErrorMessage(),
                downloadUrl
        );
    }
}
