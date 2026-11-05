package com.intelligentresume.ai.task.domain;

/**
 * AI 任务状态机。对应 DDL {@code ai_task.status} 列(以字符串存库)。
 *
 * <p>要点:
 * <ul>
 *   <li>主流程:PENDING → RUNNING → SUCCESS / FAILED。RUNNING 只能由领取租约的 UPDATE 写入,
 *       业务代码不要直接 set 成 RUNNING。</li>
 *   <li>FAILED 是终态:系统不自动重试,只有用户手动重试(AiTaskService#retry)才会被改回 PENDING。</li>
 *   <li>CANCELLED 由账号删除流程批量写入({@code cancelActiveByUserId}),同时清空租约。</li>
 *   <li>改枚举值等于改数据库已有数据:名字直接落库,增删改都要配套迁移。</li>
 * </ul>
 */
public enum AiTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
