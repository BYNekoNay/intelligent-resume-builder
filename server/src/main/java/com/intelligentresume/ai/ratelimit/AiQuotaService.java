package com.intelligentresume.ai.ratelimit;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.task.service.AiTaskCapabilityRegistry;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.observability.AppObservability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 任务配额服务 —— 按「用户 + 任务类型 + 自然日」限制可发起的任务数。
 *
 * <p>这是成本闸门,不是业务校验:超限时抛 RATE_LIMITED,调用方(创建任务/重试)必须让它冒泡到网关。
 *
 * <p>要点 / 红线:
 * <ul>
 *   <li>计数口径是"尝试数"而不是"任务行数":SQL 里 {@code retry_count < 1 ? 1 : retry_count},
 *       也就是说一个被重试过 N 次的任务算 N 次消耗。改这条 SQL 会同时改变配额语义,
 *       而监控 gauge({@code sumAttemptsGroupedByTaskType})用的是同一口径,必须同步改。</li>
 *   <li>窗口是"服务器本地时区的今天 0 点起",不是滚动 24 小时。跨时区部署会得到不同的切日点。</li>
 *   <li>{@code JOB_MATERIAL_SELECTION} 与 {@code JOB_GENERATION} 共用同一个 {@code jobGeneration}
 *       配额值,两者不独立计数。</li>
 *   <li>没有登记配额的任务类型直接抛 VALIDATION 而不是放行 —— 新增任务类型时必须同时加配置键。</li>
 *   <li>本类只做"读+判定",不写任何计数行;真正占用配额靠调用方随后插入的 ai_task 行。因此
 *       同一瞬间的并发请求可能同时通过检查(检查与写入之间没有加锁)。</li>
 * </ul>
 */
@Service
public class AiQuotaService {

    private final AiTaskRepository taskRepository;
    private final Map<AiTaskType, Integer> quotas;
    private final AppObservability observability;

    public AiQuotaService(
            AiTaskRepository taskRepository,
            @Value("${app.ai.quota.JOB_GENERATION:30}") int jobGeneration,
            @Value("${app.ai.quota.RESUME_OPTIMIZE:30}") int resumeOptimize,
            @Value("${app.ai.quota.INLINE_OPTIMIZE:60}") int inlineOptimize,
            @Value("${app.ai.quota.MATERIAL_IMPORT:5}") int materialImport,
            @Value("${app.ai.quota.ACHIEVEMENT_GUIDANCE:10}") int achievementGuidance,
            @Value("${app.ai.quota.COMMUNICATION_GENERATE:10}") int communicationGenerate,
            @Value("${app.ai.quota.INTERVIEW_COACH:60}") int interviewCoach,
            @Value("${app.ai.quota.ATS_ANALYSIS:20}") int atsAnalysis,
            AppObservability observability) {
        this.taskRepository = taskRepository;
        this.observability = observability;
        this.quotas = Map.of(
                AiTaskType.JOB_MATERIAL_SELECTION, jobGeneration,
                AiTaskType.JOB_GENERATION, jobGeneration,
                AiTaskType.RESUME_OPTIMIZE, resumeOptimize,
                AiTaskType.INLINE_OPTIMIZE, inlineOptimize,
                AiTaskType.MATERIAL_IMPORT, materialImport,
                AiTaskType.ACHIEVEMENT_GUIDANCE, achievementGuidance,
                AiTaskType.COMMUNICATION_GENERATE, communicationGenerate,
                AiTaskType.INTERVIEW_COACH, interviewCoach,
                AiTaskType.ATS_ANALYSIS, atsAnalysis
        );
        quotas.forEach(this.observability::registerQuotaLimit);
    }

    /**
     * 检查用户今日配额。超限时抛出 RATE_LIMITED。
     *
     * <p>顺序:先确认任务类型已注册(拼错类型会得到 VALIDATION 而不是"配额为 0"),
     * 再读限额(缺配置也是 VALIDATION),最后才查库计数。
     */
    @Transactional(readOnly = true)
    public void check(Long userId, AiTaskType type) {
        AiTaskCapabilityRegistry.requireRegistered(type);
        Integer limit = quotas.get(type);
        if (limit == null) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "AI quota is not registered for task type: " + type.name());
        }
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long count = taskRepository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(userId, type, startOfToday);
        if (count >= limit) {
            observability.recordQuotaRejected(type);
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 任务配额已用完");
        }
    }
}
