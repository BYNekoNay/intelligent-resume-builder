package com.intelligentresume.ai.ratelimit;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
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
 * AI 任务配额服务。按用户 + 任务类型 + 自然日限流。
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
                AiTaskType.COMMUNICATION_GENERATE, communicationGenerate
        );
        quotas.forEach(this.observability::registerQuotaLimit);
    }

    /**
     * 检查用户今日配额。超限时抛出 RATE_LIMITED。
     */
    @Transactional(readOnly = true)
    public void check(Long userId, AiTaskType type) {
        int limit = quotas.getOrDefault(type, 30);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long count = taskRepository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(userId, type, startOfToday);
        if (count >= limit) {
            observability.recordQuotaRejected(type);
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 任务配额已用完");
        }
    }
}
