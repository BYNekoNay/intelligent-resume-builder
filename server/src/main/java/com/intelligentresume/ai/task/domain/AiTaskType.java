package com.intelligentresume.ai.task.domain;

/**
 * AI 任务类型。对应 DDL {@code ai_task.task_type} 列。
 *
 * <p>要点:
 * <ul>
 *   <li>新增一个枚举值不等于自动可用:必须在 {@code AiTaskCapabilityRegistry} 注册描述符,
 *       否则应用启动时那段完整性校验会直接抛 {@code IllegalStateException}(故意 fail-closed)。</li>
 *   <li>同时要决定它属于 HEAVY 还是 LIGHT 分组(影响工作器线程池与并发额度),
 *       以及是否允许走通用 {@code POST /api/ai/tasks} 端点。</li>
 *   <li>配额键按类型配置({@code app.ai.quota.<TYPE>}),漏配会在 {@code AiQuotaService} 抛 VALIDATION。</li>
 *   <li>名字直接以字符串落库,重命名等于改历史数据。</li>
 * </ul>
 */
public enum AiTaskType {
    JOB_MATERIAL_SELECTION,
    JOB_GENERATION,
    RESUME_OPTIMIZE,
    INLINE_OPTIMIZE,
    MATERIAL_IMPORT,
    ACHIEVEMENT_GUIDANCE,
    COMMUNICATION_GENERATE,
    INTERVIEW_COACH,
    ATS_ANALYSIS
}
