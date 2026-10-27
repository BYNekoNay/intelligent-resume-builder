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
 * ATS 的「AI 侧」：启动、重试、发送前预览，以及失败时的兜底映射（R32 / D-AI-11）。
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
