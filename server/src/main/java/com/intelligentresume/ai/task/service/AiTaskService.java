package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.dto.AiTaskContinuationResponse;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.worker.AiTaskWorkerProperties;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.error.PublicFailureCopy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/**
 * AI 任务服务 —— 任务的创建(含同意校验、配额、幂等)与读取。
 *
 * <p>【业务问题】用户点"开始生成"后不能干等 AI 跑几分钟，所以这里把请求变成一条
 * PENDING 任务并立刻返回 taskId，前端拿着 taskId 轮询进度。创建前要拦住四类不该发出去的请求：
 * 没授权的、超配额的、重复提交的、内容被改过的。</p>
 *
 * <p>【链路位置】前端 → {@code AiTaskController} → 本类 → {@code AiConsentService}(查授权)、
 * {@code AiQuotaService}(查配额)、{@code IdempotencyService}(算指纹) → 落库 {@code ai_task} 表。
 * 执行不归它管：任务落库后由 {@code DatabaseTaskWorker} 异步领取。
 * {@code get} 是前端轮询的读取口；{@code retry} 是失败任务的用户手动重试入口。</p>
 *
 * <p>【改坏它会怎样】步骤顺序被打乱会导致：被拒请求也扣配额、重复提交创建重复任务、
 * 越权读到别人的任务；错误文案不类别化会把模型内部报错原文暴露给前端；
 * 刻意不加外层事务这一点若被"顺手修好"，并发同键创建反而会出问题。</p>
 *
 * <p>要点 / 红线:</p>
 * <ul>
 *   <li>创建流程的步骤顺序是语义的一部分,不能随意调换:同意 → 指纹 → 幂等重放判定 →
 *       调用方守卫(previewToken) → 配额 → 落库。被拒的请求既不消耗配额,也不产生 ai_task 行。</li>
 *   <li>"先放行重放、再查配额"是有意的:同一个幂等键的重试不该因为配额刚用完而失败,
 *       它本来就不会新增消耗。</li>
 *   <li>刻意不加外层事务:并发同键插入的唯一键冲突会污染持久化上下文,幂等回读需要干净会话。</li>
 *   <li>{@code errorMessage} 落库的是 provider/异常原文,出网关前必须经
 *       {@code PublicFailureCopy} 类别化,否则会把内部细节泄露给前端。</li>
 *   <li>所有按 id 的读写都带 userId 过滤;越权与不存在同一处理成 NOT_FOUND,不泄露存在性。</li>
 * </ul>
 */
@Service
public class AiTaskService {

    private final AiTaskRepository taskRepository;
    private final AiConsentService consentService;
    private final AiQuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final AiTaskWorkerProperties workerProperties;
    private final PublicFailureCopy publicFailureCopy;

    public AiTaskService(AiTaskRepository taskRepository,
                         AiConsentService consentService,
                         AiQuotaService quotaService,
                         IdempotencyService idempotencyService,
                         AiTaskWorkerProperties workerProperties,
                         PublicFailureCopy publicFailureCopy) {
        this.taskRepository = taskRepository;
        this.consentService = consentService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.workerProperties = workerProperties;
        this.publicFailureCopy = publicFailureCopy;
    }

    /**
     * 创建 AI 任务（幂等键 = 客户端带来的 {@code Idempotency-Key} 请求头，用来标记"这是同一次提交"）。
     *
     * <ol>
     *   <li>校验 AI 同意 —— 用户没点过授权，资料一个字都不能发给模型；</li>
     *   <li>计算请求指纹 —— 把输入快照压成一个短哈希，作为"内容有没有变"的判据；</li>
     *   <li>幂等性检查 —— 相同幂等键 + 相同指纹 = 网络重试，直接返回已有任务不重复创建；
     *       相同键 + 不同指纹 = 客户端偷偷改了内容，抛 CONFLICT。这一步必须在校验配额之前：
     *       重放不新增消耗，不该因配额刚用完而被拒；</li>
     *   <li>执行调用方守卫（如预览令牌校验）—— 只对"真正要新建"的请求生效，被拒不留任务行；</li>
     *   <li>校验配额 —— 今天这类任务的额度用完了就拒绝（真正的消耗体现在任务行的尝试数上）；</li>
     *   <li>创建 PENDING 任务；两个并发请求同键撞唯一键时，回读先落库的赢家并按幂等语义响应。</li>
     * </ol>
     *
     * <p>为什么顺序不能换：同意、指纹、幂等判定都必须在任何消耗之前；守卫必须在写入之前
     * 才能保证"拒绝不留任务行"；配额最后查，才能让重放请求绕过额度不足的误拒。</p>
     *
     * <p>刻意不加外层事务：并发同键创建的唯一键冲突会污染当前持久化上下文，
     * 而幂等回读必须基于干净会话；本流程只有一次写入（saveAndFlush），
     * 各步骤各自事务即可保证一致。</p>
     */
    public AiTaskStatusResponse create(CreateAiTaskRequest req, String idempotencyKey, Long userId) {
        return create(req, idempotencyKey, userId, () -> { });
    }

    /**
     * 同 {@link #create(CreateAiTaskRequest, String, Long)}，额外在**幂等重放判定之后、任何写入之前**
     * 执行一次守卫。
     *
     * <p>领域端点用它做 R32 预览令牌校验（AC-R32-9 / AC-R32-10）：
     * <ul>
     *   <li>放在重放判定之后 —— 同一幂等键的客户端重试不该因为没带令牌而失败；</li>
     *   <li>放在配额与写入之前 —— 被拒绝的请求不消耗配额、不产生 {@code ai_task} 行。</li>
     * </ul>
     *
     * @param beforeCreate 守卫，通常是一次 {@code AiPreviewGuard#require} 调用；重放分支不执行
     */
    public AiTaskStatusResponse create(CreateAiTaskRequest req, String idempotencyKey, Long userId,
                                       Runnable beforeCreate) {
        AiTaskCapabilityRegistry.requireRegistered(req.taskType());
        Map<String, Object> inputSnapshot = buildInputSnapshot(req);

        // 1. 校验同意
        if (!consentService.hasValidConsent(userId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        List<String> requiredCategories = AiTaskConsentPolicy.requiredCategories(req.taskType(), inputSnapshot);
        if (!consentService.hasValidConsent(userId, req.taskType().name(), requiredCategories)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED,
                    "AI authorization does not cover this task or its data categories");
        }

        // 2. 校验配额
        // 3. 计算指纹
        String fingerprint = idempotencyService.fingerprint(inputSnapshot);

        // 4. 幂等性检查
        Optional<AiTask> existing = taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                userId, req.taskType(), idempotencyKey);
        if (existing.isPresent()) {
            AiTask task = existing.get();
            if (task.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(task);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "相同幂等键的请求内容不一致");
        }

        beforeCreate.run();

        quotaService.check(userId, req.taskType());

        // 5. 创建任务
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(req.taskType());
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestFingerprint(fingerprint);
        task.setInputSnapshotJson(inputSnapshot);
        task.setStatus(AiTaskStatus.PENDING);
        task.setRetryCount(0);

        try {
            task = taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException conflict) {
            // 并发同键竞态：另一请求已成为赢家（唯一键 uk_ai_task_idem）。
            // 按幂等语义回读赢家：同指纹 → 返回同一任务；不同 → 409；非本竞态 → 原样上抛。
            AiTask winner = taskRepository
                    .findByUserIdAndTaskTypeAndIdempotencyKey(userId, req.taskType(), idempotencyKey)
                    .orElseThrow(() -> conflict);
            if (winner.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(winner);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "相同幂等键的请求内容不一致");
        }
        return toResponse(task);
    }

    /**
     * 查询任务状态 —— 前端轮询（每隔一两秒调一次 GET，直到状态变成 SUCCESS/FAILED）的入口。
     *
     * <p>返回的 errorMessage 已经过类别化处理,与库里的原文不是同一份(原文仍在库与日志里)。</p>
     */
    @Transactional(readOnly = true)
    public AiTaskStatusResponse get(Long id, Long userId) {
        AiTask task = taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 任务不存在"));
        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public List<AiTaskContinuationResponse> listContinuations(Long userId) {
        // 读模型投影（ideation #52）：元数据-only，不加载 resultJson/inputSnapshotJson。
        return taskRepository.findContinuationRowsByUserId(userId).stream()
                .map(row -> new AiTaskContinuationResponse(
                        row.getId(), row.getTaskType(), row.getParentTaskId(), row.getStatus(),
                        normalizeConfirmationStatus(row.getConfirmationStatus()),
                        row.getResultResumeVersionId(), row.getRetryCount(),
                        row.getCreatedAt(), row.getUpdatedAt()))
                .toList();
    }

    /**
     * 组装用于幂等指纹与 prompt 的输入快照。
     *
     * <p>只有非空字段才写入,空串与 null 等价(避免"传了空 jdText"和"没传"被算成两个不同的请求)。
     * 这个 map 会原样落进 {@code input_snapshot_json},也是下游 {@code AiTaskConsentPolicy}
     * 判断"是否含 JD"的依据。
     */
    private Map<String, Object> buildInputSnapshot(CreateAiTaskRequest req) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("taskType", req.taskType().name());
        if (req.input() != null) {
            snapshot.put("input", req.input());
        }
        if (req.targetResumeId() != null) {
            snapshot.put("targetResumeId", req.targetResumeId());
        }
        if (req.jobDescriptionId() != null) {
            snapshot.put("jobDescriptionId", req.jobDescriptionId());
        }
        if (req.jdText() != null && !req.jdText().isBlank()) {
            snapshot.put("jdText", req.jdText());
        }
        if (req.companyName() != null && !req.companyName().isBlank()) {
            snapshot.put("companyName", req.companyName());
        }
        if (req.positionTitle() != null && !req.positionTitle().isBlank()) {
            snapshot.put("positionTitle", req.positionTitle());
        }
        if (req.resumeTitle() != null && !req.resumeTitle().isBlank()) {
            snapshot.put("resumeTitle", req.resumeTitle());
        }
        return snapshot;
    }

    public AiTaskStatusResponse toResponse(AiTask task) {
        AiTaskCapabilityRegistry.requireRegistered(task.getTaskType());
        return new AiTaskStatusResponse(
                task.getId(),
                task.getTaskType(),
                task.getParentTaskId(),
                toLong(task.getInputSnapshotJson().get("jobDescriptionId")),
                task.getStatus(),
                task.getResultJson(),
                // 公开文案边界：落库的是 provider/异常原文，只允许类别化的稳定文案出网关（原文留在库与日志）
                publicFailureCopy.forAiTask(task.getErrorMessage()),
                normalizeConfirmationStatus(task.getConfirmationStatus()),
                task.getResultResumeVersionId(),
                task.getRetryCount(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    /**
     * Historical tasks predate the explicit NOT_REQUIRED value and persist a
     * null confirmation status. Keep that database representation readable,
     * but expose one stable API contract to the Web client.
     */
    private ConfirmationStatus normalizeConfirmationStatus(ConfirmationStatus status) {
        return status == null ? ConfirmationStatus.NOT_REQUIRED : status;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // A malformed legacy snapshot should not make task status unreadable.
            }
        }
        return null;
    }

    /**
     * 用户手动重试失败任务:把它退回 PENDING 交给工作器重新领取。
     *
     * <p>注意 {@code retryCount} **不会被清零**,所以它同时限制着"用户还能点几次"
     * ({@code maxRetries})和工作器的"崩溃循环收口"({@code maxAttempts})。</p>
     *
     * <p>重试前要重新过同意与配额:用户可能在此期间撤回了授权,或当日额度已用完;
     * 这与 R33"不自动重试"是配套的 —— 消耗必须由人明确发起。</p>
     */
    @Transactional
    public AiTaskStatusResponse retry(Long taskId, Long userId) {
        AiTask task = taskRepository.findByIdAndUserId(taskId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 任务不存在"));
        AiTaskCapabilityRegistry.requireRegistered(task.getTaskType());
        if (!com.intelligentresume.ai.task.domain.AiTaskStatus.FAILED.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的任务可以重试");
        }
        // ATS_ANALYSIS 豁免 maxRetries 上限:其失败多因模型超时等瞬时故障,允许用户手动多次重试。
        // 实际消耗仍受 app.ai.quota.ATS_ANALYSIS 日配额间接封顶,不会无限重试。
        if (task.getRetryCount() >= workerProperties.getMaxRetries()
                && task.getTaskType() != com.intelligentresume.ai.task.domain.AiTaskType.ATS_ANALYSIS) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 任务已达到最大重试次数");
        }
        if (!consentService.hasValidConsent(userId)
                || !consentService.hasValidConsent(userId, task.getTaskType().name(),
                AiTaskConsentPolicy.requiredCategories(task.getTaskType(), task.getInputSnapshotJson()))) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        quotaService.check(userId, task.getTaskType());
        task.setStatus(com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING);
        task.setErrorMessage(null);
        taskRepository.save(task);
        return toResponse(task);
    }

    /**
     * 用户侧「清空 AI 任务历史」（ideation #26）：删除本人的终态任务，返回删除条数。
     *
     * <p>进行中与待确认任务保留——前者仍被工作器处理，后者用户还没在确认页处理完。
     * 超过保留期的任务另由 {@link AiTaskRetentionService} 压缩为元数据（不删除行）。
     */
    @Transactional
    public int deleteHistory(Long userId) {
        return taskRepository.deleteTerminalHistoryByUserId(userId);
    }

}
