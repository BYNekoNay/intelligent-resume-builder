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

/**
 * Polls and renders PDF export tasks after atomically claiming a short lease.
 *
 * <h2>① 解决什么业务问题</h2>
 * 用户提交导出请求后，总得有"人"去真正生成 PDF。本类就是那个后台工人：
 * 每隔几秒醒来，领一批待处理的导出任务，渲染 PDF、存文件、把结果写回任务行。
 *
 * <h2>② 在链路哪里</h2>
 * 上游：{@code ExportService.create} 建的 PENDING 任务行是它的原料；
 * 它通过 {@link ExportTaskLeaseService} 领任务、回写结果；
 * 渲染走 {@code PdfServiceClient}（在线版，pdf-service 用 Chrome 打印）或
 * {@code JavaResumePdfRenderer}（桌面版，纯 Java）；产物经 {@code ExportStorageService} 落盘。
 *
 * <h2>③ 改坏它用户会看到什么</h2>
 * 领取不用原子租约 → 多实例部署时同一份简历被渲染两次甚至写坏结果；
 * 容量拒绝改成直接失败 → 高峰期用户的导出全部变成"失败"而不是"排队"；
 * 孤儿文件清理去掉 → 存储里堆满没有任务引用的 PDF，且永远不会被过期作业清理。
 *
 * <p>导出任务执行器：定时领取一批任务，校验用户/版本/内容后渲染 PDF 并落盘。
 *
 * <p>要点：
 * <ul>
 *     <li>领取靠 {@link ExportTaskLeaseService#claimBatch} 的原子租约（租约 = 一段"这个任务归我管"的
 *         有期限占用权，超时未释放别人就可以接管），多实例下同一行只会被一个实例渲染。</li>
 *     <li>{@code poll()} 只负责"领一批 + 逐条处理"：领取本身的异常被吞掉并记日志，
 *         单条任务的失败在 {@code processTask} 内部落成 FAILED，不影响同批其它任务。</li>
 *     <li>渲染路径二选一：桌面模式走纯 Java 渲染器，否则走 pdf-service；两条路径的模板与内容模型必须保持一致。</li>
 *     <li>成功写入被丢弃（租约已被接管）时必须删掉刚生成的文件，否则它会成为无人引用的孤儿。</li>
 *     <li>容量拒绝（503）走"重排"而不是"失败"，只有试到 {@code max-attempts} 才终态失败。</li>
 *     <li>{@code retryCount} 在领取时已经自增，所以容量分支判断的是"已尝试次数"。</li>
 * </ul>
 */
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

    /**
     * 定时轮询：每次领一批（默认 1 条），逐条处理。
     *
     * <p>单条任务失败在 {@link #processTask} 内部被吃掉并落成 FAILED，不会影响同批其它任务；
     * 这里再包一层是为了兜住"领取本身"的异常（数据库不可用等）。
     */
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

    /**
     * 处理单条已领取的任务。步骤：
     * ① 三道确定性闸门（任何一道不过都直接判失败、不重试，因为重试也不会变好）：
     *    用户被禁用/注销 → 版本不存在或已归档 → 简历内容为空；
     * ② 渲染 PDF：桌面模式走纯 Java 渲染器（不用 Node + Chromium），在线版走 pdf-service；
     * ③ 把文件落盘，再向租约服务申报"成功"——注意顺序是先存文件后写库：
     *    数据库行引用的 storageKey 必须指向一个真实存在的文件；
     * ④ 申报被拒（租约已被别的实例接管）→ 本次产物成了孤儿文件，立即删掉；
     * ⑤ 容量拒绝（下游说"现在没名额"）→ 按 Retry-After 延迟重排，试到上限才判终态失败；
     * ⑥ 其它异常 → 记分类日志并落 FAILED；无论结局如何，finally 里上报一次统计指标。
     *
     * <p>渲染前的三道闸门都直接判失败、不重试：用户被禁用/注销、版本不存在或已归档、内容为空——
     * 这些是"重试也不会变好"的确定性失败。只有下游异常才可能值得重排。
     *
     * <p>{@code finally} 里记录指标：成功与否取的是任务对象上的最终状态（由租约服务回写），
     * 因此成功/失败/容量重排三种结局都会被统计到。
     */
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
