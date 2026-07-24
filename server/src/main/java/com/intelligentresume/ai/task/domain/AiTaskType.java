package com.intelligentresume.ai.task.domain;

/**
 * AI 任务类型。对应 DDL {@code ai_task.task_type} 列。
 */
public enum AiTaskType {
    JOB_GENERATION,
    RESUME_OPTIMIZE,
    INLINE_OPTIMIZE,
    MATERIAL_IMPORT,
    ACHIEVEMENT_GUIDANCE,
    COMMUNICATION_GENERATE
}
