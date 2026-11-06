package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.error.PublicFailureCopy;
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
import java.util.List;

/**
 * 导出服务。编排创建、查询、下载流程。
 *
 * <h2>① 解决什么业务问题</h2>
 * 用户想把某份简历按某个模板导出成 PDF 下载走。渲染耗时不能让用户干等，
 * 所以走异步：这里只负责"受理"（建任务）和"交付"（查进度、给文件），
 * 真正生成 PDF 的是后台的 {@code ExportTaskWorker}。
 *
 * <h2>② 在链路哪里</h2>
 * 被 {@code ExportController} 调用（HTTP /api/exports 的后端）；
 * 它写入 {@code export_task} 表，{@code ExportTaskWorker} 领任务渲染，
 * 渲染出的文件经 {@code ExportStorageService} 存取；产出 {@code ExportTaskStatusResponse} 给前端。
 *
 * <h2>③ 改坏它用户会看到什么</h2>
 * 归属校验删掉 → 用户能下载别人的简历 PDF（严重隐私事故）；
 * 复用逻辑去掉 → 同一版本狂点导出会重复排队、重复渲染；
 * 过期检查去掉 → 用户下载到早已该销毁的旧文件；
 * retry 次数限制去掉 → 用户可无限次触发真实渲染，拖垮后台。
 *
 * <p>职责一句话：把"用户点了导出"变成一条任务行，并保证下载只发生在"属于你、已成功、未过期"的前提下。
 *
 * <p>关键不变量:
 * <ul>
 *   <li>templateCode 必须属于统一支持列表</li>
 *   <li>跨用户 → NOT_FOUND(不泄露存在性)；归属校验必须排在归档校验之前</li>
 *   <li>下载必须 status=SUCCESS 且未过期</li>
 *   <li>不暴露 storageKey（文件在存储里的真实路径——泄露等于把文件门禁绕掉）</li>
 *   <li>落库的 errorMessage 可能是上游原文，出口一律经 {@code PublicFailureCopy} 转成稳定文案</li>
 * </ul>
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final ExportTaskRepository exportTaskRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final ExportStorageService storageService;
    private final ExportExpiryService expiryService;
    private final PublicFailureCopy publicFailureCopy;
    private final long fileTtlHours;
    private final int maxManualRetries;

    public ExportService(ExportTaskRepository exportTaskRepository,
                         ResumeVersionRepository resumeVersionRepository,
                         ExportStorageService storageService,
                         ExportExpiryService expiryService,
                         PublicFailureCopy publicFailureCopy,
                         @Value("${app.pdf.file-ttl-hours:24}") long fileTtlHours,
                         @Value("${app.pdf.worker.max-manual-retries:5}") int maxManualRetries) {
        this.exportTaskRepository = exportTaskRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.storageService = storageService;
        this.expiryService = expiryService;
        this.publicFailureCopy = publicFailureCopy;
        this.fileTtlHours = fileTtlHours;
        this.maxManualRetries = maxManualRetries;
    }

    /**
     * 创建导出任务。校验简历版本归属后创建 PENDING 任务,由 worker 异步渲染。
     *
     * <p>步骤：①模板白名单校验 → ②归属校验 → ③归档校验 → ④可复用任务检查 → ⑤建 PENDING 任务行。
     * 前三步的顺序是刻意的：先模板白名单 → 再归属 → 再归档状态，保证"不是你的版本"永远只会得到 404，
     * 不会因为归档与否而暴露它存在（信息泄露：如果他人版本报"已归档"、不存在报"不存在"，
     * 攻击者就能靠报错差异探测别人有哪些版本）。
     *
     * <p>命中可复用任务时直接返回旧任务（可能已在 RUNNING/SUCCESS），不会重复排队。
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
        // 归档是可逆状态：与 ATS/评分/投递/沟通/面试一致用 409 + 可操作文案（归属校验在前，不泄露归档状态）
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再发起导出");
        }

        // 3. 结果复用（#14）：同一用户 + 同一版本 + 同一模板 已有未过期的在途/成功任务时直接复用，
        //    PDF 内容由（版本, 模板）唯一决定，重复排队只会重复渲染。
        ExportTask reusable = exportTaskRepository
                .findFirstByUserIdAndResumeVersionIdAndTemplateCodeAndStatusInOrderByIdDesc(
                        userId, req.resumeVersionId(), req.templateCode(),
                        List.of(ExportStatus.PENDING, ExportStatus.RUNNING, ExportStatus.SUCCESS))
                .orElse(null);
        if (reusable != null && isReusable(reusable)) {
            log.debug("Reusing export task: id={}, versionId={}", reusable.getId(), req.resumeVersionId());
            return toResponse(reusable);
        }

        // 4. 创建任务
        ExportTask task = new ExportTask();
        task.setUserId(userId);
        task.setResumeVersionId(req.resumeVersionId());
        task.setTemplateCode(req.templateCode());
        task.setStatus(ExportStatus.PENDING);
        task.setExpiresAt(LocalDateTime.now().plusHours(fileTtlHours));
        exportTaskRepository.save(task);

        // 隐私约束（PROJECT_CONTEXT.md）：用户 ID 不得写入日志；taskId/versionId 足够排障。
        log.debug("Export task created: id={}, versionId={}", task.getId(), req.resumeVersionId());
        return toResponse(task);
    }

    /**
     * 查询导出任务状态。
     *
     * <p>有副作用：查到已过期（SUCCESS 且 expiresAt 已过）会把任务落成 EXPIRED 并删文件，
     * 见 {@link #resolveExpiry}。因此它不是只读接口，不能加 {@code readOnly}。
     */
    public ExportTaskStatusResponse get(Long taskId, Long userId) {
        return toResponse(resolveExpiry(findForUser(taskId, userId), userId));
    }

    /**
     * 下载 PDF 文件。受控流:校验归属、状态、过期。
     */
    public Resource download(Long taskId, Long userId) {
        ExportTask task = resolveExpiry(findForUser(taskId, userId), userId);

        if (task.getStatus() == ExportStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期");
        }
        // 必须 SUCCESS
        if (task.getStatus() != ExportStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不可用");
        }

        // 读取文件
        byte[] content = storageService.read(task.getStorageKey());
        if (content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在");
        }

        return new ByteArrayResource(content);
    }

    /**
     * 重试失败的导出任务。步骤：①确认任务属于你且状态是 FAILED（别的状态重试没有意义）；
     * ②检查手动重试次数是否超上限（超限就让用户"重新发起导出"——生成新任务行，
     * 顺带区分"这行本身有问题"和"下游临时故障"）；③把任务推回 PENDING、清错误信息、
     * 计一次重试、清掉延迟时间、刷新过期时间；④保存，等 worker 再领走。
     */
    @Transactional
    public ExportTaskStatusResponse retry(Long taskId, Long userId) {
        ExportTask task = findForUser(taskId, userId);
        if (task.getStatus() != ExportStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的任务可以重试");
        }
        // 手动重试此前无次数上限：同一行可以被无限次推回 PENDING，每次都真跑一遍渲染。
        // 与 AI 任务（AiTaskService.retry 的 maxRetries）对齐，超限时让用户重新发起导出——
        // 重新发起会生成新行，也顺带把「这一行本身有问题」和「下游临时故障」区分开。
        // retryCount 的 null 口径统一：DB 列 NOT NULL DEFAULT 0（V1），null 仅在异常数据/防御场景出现，
        // 视为 0 —— 上面的限次检查和这里的自增必须同一口径，否则检查挡住 null、自增却抛 NPE。
        int currentRetryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
        if (currentRetryCount >= maxManualRetries) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该导出任务已重试 " + currentRetryCount + " 次，请重新发起导出");
        }
        task.setStatus(ExportStatus.PENDING);
        task.setErrorMessage(null);
        task.setRetryCount(currentRetryCount + 1);
        // 手动重试是「用户现在就要」，不受容量拒绝留下的延迟约束。
        task.setNextAttemptAt(null);
        task.setExpiresAt(LocalDateTime.now().plusHours(fileTtlHours));
        exportTaskRepository.save(task);
        return toResponse(task);
    }

    /** 在途任务总是可复用；SUCCESS 仅当文件尚未过期时可复用。 */
    private boolean isReusable(ExportTask task) {
        if (task.getStatus() != ExportStatus.SUCCESS) return true;
        return task.getExpiresAt() != null && task.getExpiresAt().isAfter(LocalDateTime.now());
    }

    /**
     * 过期落地（#54）：只有确实写入 EXPIRED 时才在返回视图上标记；若行已被并发操作推进
     * （例如过期前重试排队、状态被其它请求改写），以数据库最新状态为准，不把新状态误报为已过期。
     */
    private ExportTask resolveExpiry(ExportTask task, Long userId) {
        if (task.getStatus() != ExportStatus.SUCCESS
                || task.getExpiresAt() == null
                || !task.getExpiresAt().isBefore(LocalDateTime.now())) {
            return task;
        }
        if (expiryService.expireIfDue(task.getId(), LocalDateTime.now())) {
            task.setStatus(ExportStatus.EXPIRED);
            task.setStorageKey(null);
            task.setFileSizeBytes(null);
            task.setSha256(null);
            return task;
        }
        return findForUser(task.getId(), userId);
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
                // 公开文案边界：落库的是 PDF 服务/异常的原文，只允许类别化的稳定文案出网关
                publicFailureCopy.forExportTask(task.getErrorMessage()),
                downloadUrl
        );
    }
}
