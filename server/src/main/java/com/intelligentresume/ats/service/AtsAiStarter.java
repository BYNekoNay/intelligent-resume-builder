package com.intelligentresume.ats.service;

import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.resume.domain.ResumeVersion;
import org.springframework.stereotype.Component;

/**
 * ATS 的「AI 侧」总管：启动、重试、发送前预览，以及失败时的兜底映射（R32 / D-AI-11）。
 *
 * <h2>① 解决什么业务问题</h2>
 * 规则分只能算"关键词有没有覆盖"，看不懂句子含义。AI 能做语义分析，但 AI 有各种失败方式
 * （没配密钥、用户没授权、配额用完、模型超时）。本类的工作就是：在正确的时机、按正确的顺序
 * 检查这些前置条件，能启动就启动 AI 任务；启动不了就给检查记录记一个"降级原因"（降级 =
 * AI 不可用时自动换成规则引擎出结果），保证用户永远能拿到规则分。
 *
 * <h2>② 在链路哪里</h2>
 * {@link AtsService} 在规则分落库后调 {@link #startOrFallback}；用户点"重试 AI"时走
 * {@link #retryOrThrow}；它创建的 AI 任务由 ai.task 模块的 worker 异步执行，
 * worker 调 {@link AtsAiAnalysisService} 干活，结论经 {@link AtsResultStateService} 写回检查记录。
 *
 * <h2>③ 改坏它用户会看到什么</h2>
 * 可用性检查和令牌检查顺序颠倒 → 没配密钥的环境会误报"你没预览"；
 * startOrFallback 改成抛异常 → 用户发起检查直接 500，连规则分都看不到；
 * 重试时对 PENDING/SUCCESS 的任务又新建一个 → 同一份简历被模型重复分析、白白消耗配额。
 *
 * <p>为什么单独成类，而不是继续写在 {@link AtsService} 里：R32 给 ATS 加了
 * 「可用性 → 令牌 → 建任务」三段编排，一次性多出五十多行；而 {@code AtsService}
 * 是登记在行数棘轮基线里的欠债文件，只许下调。把**新**逻辑塞进旧欠债文件，
 * 等于让棘轮一边还债一边欠新的 —— 这正是棘轮要挡的行为。
 *
 * <p>职责边界很清晰：本类只处理 AI 相关的事（要不要发、能不能发、发出去什么），
 * 实体查找与归属校验留在 {@code AtsService}（它还要拿这些实体算规则分）。
 *
 * <p>两段式启动是 ATS 独有，见计划文档 §7.8 —— prompt 正文含
 * {@code atsCheckResultId}（worker 靠它写回结论），导致「预览必须先有 check」，
 * 于是把「检查」与「启动 AI」拆成两个端点。
 *
 * <p>要点（两个入口的语义差别是刻意的）：
 * <ul>
 *   <li>{@link #startOrFallback}：首次检查时调用，<b>任何问题都不抛给客户端</b>，一律落兜底
 *       （AI_DISABLED / PREVIEW_REQUIRED / CONSENT_REQUIRED / QUOTA_EXCEEDED / UNKNOWN）。
 *       规则结果已经在库里，客户端总能拿到分数。</li>
 *   <li>{@link #retryOrThrow}：用户主动重试时调用，<b>令牌校验在 try 之外</b>，缺令牌/令牌不符直接抛
 *       400/409；只有创建任务阶段的预期错误才转兜底。放进去就会被吞成"静默不跑 AI 还返回 200"。</li>
 *   <li>可用性检查必须排在令牌之前：无密钥环境要落 AI_DISABLED，抢在令牌前报错会把"服务没配"
 *       误报成"你没预览"。</li>
 *   <li>创建任务用固定幂等键 {@code ats:<checkId>:v1}（同一个检查永远生成同一个键，
 *       AI 任务服务靠它去重）：一个 check 只会对应一条 ATS 分析任务。</li>
 *   <li>重试时如果已有任务处于 PENDING/RUNNING/SUCCESS，只同步状态、不新建任务；
 *       只有 FAILED 才调用 {@code taskService.retry}。</li>
 * </ul>
 */
@Component
public class AtsAiStarter {

    private final AiProviderRegistry providerRegistry;
    private final AiTaskService taskService;
    private final AtsResultStateService stateService;
    private final AtsAiInputAssembler inputAssembler;
    private final AiPreviewService previewService;
    private final AiPreviewGuard previewGuard;

    public AtsAiStarter(AiProviderRegistry providerRegistry, AiTaskService taskService,
                        AtsResultStateService stateService, AtsAiInputAssembler inputAssembler,
                        AiPreviewService previewService, AiPreviewGuard previewGuard) {
        this.providerRegistry = providerRegistry;
        this.taskService = taskService;
        this.stateService = stateService;
        this.inputAssembler = inputAssembler;
        this.previewService = previewService;
        this.previewGuard = previewGuard;
    }

    /**
     * 首次检查阶段启动 AI：任何一步走不通都只落兜底，**不抛**给客户端。
     *
     * <p>顺序：可用性 → 令牌 → 建任务。可用性在最前，因为无密钥环境要先落成
     * {@code AI_DISABLED}，令牌校验不能抢在它前面把"服务没配"误报成"你没预览"。
     */
    public void startOrFallback(AtsCheckResult result, ResumeVersion version, JobDescription job,
                                Long userId, String previewToken) {
        if (!isAvailable()) {
            markFallback(result, userId, null, AtsFallbackCode.AI_DISABLED,
                    "AI 服务尚未配置，已使用本地规则结果。", false, false);
            return;
        }
        // R32：没有预览令牌就不发起 AI —— 规则结果照常保留，由客户端预览后走 ai-retry
        AtsAiInputAssembler.PreparedInput prepared = inputAssembler.prepare(result, version, job);
        try {
            previewGuard.require(AiTaskType.ATS_ANALYSIS, prepared.providerInput(), previewToken);
        } catch (BusinessException e) {
            markFallback(result, userId, null, AtsFallbackCode.PREVIEW_REQUIRED,
                    "AI 分析需要先预览并确认将要发送的内容。", true, false);
            return;
        }
        try {
            createTask(result, userId, prepared);
        } catch (BusinessException e) {
            applyExpectedFallback(result, userId, null, e);
        } catch (RuntimeException e) {
            markFallback(result, userId, null, AtsFallbackCode.UNKNOWN,
                    "AI 分析未能启动，已使用本地规则结果。", true, false);
        }
    }

    /**
     * 用户显式重试：与 {@link #startOrFallback} 的区别是**令牌问题必须抛出**。
     *
     * <p>本端点的语义就是"我现在要发 AI"，缺令牌或令牌与载荷不符是协议错误，
     * 必须 40001/40901 抛给客户端；若在这里也兜底，就成了"静默不跑 AI 但报 200"，
     * 客户端永远发现不了自己漏了确认这一步。
     *
     * <p>另外：这里只捕获 {@link BusinessException}，其余 {@code RuntimeException} 会直接冒泡
     * （{@code startOrFallback} 则会兜住并落 UNKNOWN）—— 两个入口对"意外异常"的处理不一样，
     * 改动时注意客户端拿到的结果会不同。
     */
    public void retryOrThrow(AtsCheckResult result, ResumeVersion version, JobDescription job,
                             Long userId, String previewToken) {
        Long taskId = AtsResultStateService.longValueOf(result.getResultJson().get("aiTaskId"));

        if (!isAvailable()) {
            markFallback(result, userId, taskId, AtsFallbackCode.AI_DISABLED,
                    "AI 服务尚未配置，已使用本地规则结果。", false, false);
            return;
        }
        AtsAiInputAssembler.PreparedInput prepared = inputAssembler.prepare(result, version, job);
        // 放在 try 之外 —— 否则 applyExpectedFallback 会把令牌异常吞成规则结果
        previewGuard.require(AiTaskType.ATS_ANALYSIS, prepared.providerInput(), previewToken);
        try {
            if (taskId != null) {
                AiTaskStatusResponse task = taskService.get(taskId, userId);
                if (task.status() == AiTaskStatus.FAILED) {
                    AiTaskStatusResponse retried = taskService.retry(taskId, userId);
                    stateService.markAnalyzing(result.getId(), userId, retried.id());
                    return;
                }
                if (task.status() == AiTaskStatus.PENDING || task.status() == AiTaskStatus.RUNNING) {
                    stateService.markAnalyzing(result.getId(), userId, task.id());
                    return;
                }
                if (task.status() == AiTaskStatus.SUCCESS) return;
            }
            createTask(result, userId, prepared);
        } catch (BusinessException e) {
            applyExpectedFallback(result, userId, taskId, e);
        }
    }

    /** 发送前预览（R32）：按 checkId 组装 —— 只读，不落库、不调 provider、不改检查状态。 */
    public AiPreviewService.PreviewResponse preview(AtsCheckResult result, ResumeVersion version,
                                                    JobDescription job) {
        AtsAiInputAssembler.PreparedInput prepared = inputAssembler.prepare(result, version, job);
        return previewService.preview(new AiPreviewService.PreviewRequest(
                AiTaskType.ATS_ANALYSIS, prepared.providerInput(), false));
    }

    private boolean isAvailable() {
        return providerRegistry.route(AiTaskType.ATS_ANALYSIS).isAvailable();
    }

    private void createTask(AtsCheckResult result, Long userId, AtsAiInputAssembler.PreparedInput prepared) {
        CreateAiTaskRequest request = new CreateAiTaskRequest(
                AiTaskType.ATS_ANALYSIS, prepared.taskInput(), null, result.getJobDescriptionId(),
                null, null, null, null);
        AiTaskStatusResponse task = taskService.create(request, "ats:" + result.getId() + ":v1", userId);
        stateService.markAnalyzing(result.getId(), userId, task.id());
    }

    private void applyExpectedFallback(AtsCheckResult result, Long userId, Long taskId, BusinessException e) {
        if (e.getErrorCode() == ErrorCode.CONSENT_REQUIRED) {
            markFallback(result, userId, taskId, AtsFallbackCode.CONSENT_REQUIRED,
                    "需要授权 AI 处理简历和岗位信息，当前已使用本地规则结果。", false, true);
        } else if (e.getErrorCode() == ErrorCode.RATE_LIMITED) {
            markFallback(result, userId, taskId, AtsFallbackCode.QUOTA_EXCEEDED,
                    "今日 AI ATS 分析配额已用完，当前已使用本地规则结果。", false, false);
        } else {
            markFallback(result, userId, taskId, AtsFallbackCode.UNKNOWN,
                    "AI 分析未能启动，已使用本地规则结果。", true, false);
        }
    }

    private void markFallback(AtsCheckResult result, Long userId, Long taskId, AtsFallbackCode code,
                              String message, boolean retryable, boolean consentRequired) {
        stateService.markFallback(result.getId(), userId, taskId, code, message,
                retryable, consentRequired);
    }
}
