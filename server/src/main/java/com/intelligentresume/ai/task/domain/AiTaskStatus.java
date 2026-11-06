package com.intelligentresume.ai.task.domain;

/**
 * AI 任务状态机 —— 一个任务从"已提交"到"出结果"的一生。对应 DDL {@code ai_task.status} 列(以字符串存库)。
 *
 * <p>各状态的人话含义：PENDING=排队中；RUNNING=某个工作器正在执行；
 * SUCCESS=出结果了；FAILED=失败了（等用户手动重试）；CANCELLED=随账号删除被批量作废。
 *
 * <p>【链路位置】PENDING 由 {@code AiTaskService#create} 写入；RUNNING 只由领取租约的 SQL 写入；
 * SUCCESS/FAILED 只由 {@code TaskLeaseService} 写入；前端轮询时读到。</p>
 *
 * <p>【改坏它会怎样】状态流转被绕过（比如业务代码直接 set RUNNING）会让租约互斥失效，
 * 出现重复执行；改枚举值名字等于改数据库已有数据，没有配套迁移会让历史任务全部读不出来。</p>
 *
 * <p>要点:</p>
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
