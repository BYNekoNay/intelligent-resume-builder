package com.intelligentresume.ai.task.domain;

/**
 * AI 任务类型 —— 平台上每一种 AI 能力的"门牌号"。对应 DDL {@code ai_task.task_type} 列。
 *
 * <p>人话对照：JOB_MATERIAL_SELECTION=为某个岗位挑素材；JOB_GENERATION=按岗位定制生成简历；
 * RESUME_OPTIMIZE=整份简历优化；INLINE_OPTIMIZE=编辑器里选中文字段润色；MATERIAL_IMPORT=素材导入成简历草稿；
 * ACHIEVEMENT_GUIDANCE=成就量化辅导；COMMUNICATION_GENERATE=求职信/跟进邮件；INTERVIEW_COACH=面试追问练习；
 * ATS_ANALYSIS=简历过机筛分析。
 *
 * <p>【链路位置】前端创建任务时指定 → 能力注册表、配额服务、同意策略、工作器全按它分发。</p>
 *
 * <p>【改坏它会怎样】新增枚举值但没在 {@code AiTaskCapabilityRegistry} 登记会让启动直接失败；
 * 重命名枚举值等于篡改历史任务的类型字段，老任务将无法识别。</p>
 *
 * <p>要点:</p>
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
