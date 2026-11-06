package com.intelligentresume.ai.task.domain;

/**
 * AI 任务结果的人工确认状态 —— 模型出的结果要不要用户点头才能用。对应 DDL {@code ai_task.confirmation_status} 列(可为 NULL)。
 *
 * <p>取值含义：NOT_REQUIRED=不需要确认；PENDING=等用户在确认页处理；CONFIRMED=用户已采纳；
 * REJECTED=用户已拒绝。
 *
 * <p>【改坏它会怎样】把它误设为 CONFIRMED 会让"AI 不自动落库"的边界失守；
 * 把 PENDING 的任务清掉会让确认页读到空结果。</p>
 *
 * <p>要点:</p>
 * <ul>
 *   <li>只有需要人工确认的任务(选材、岗位定制生成)在 worker 成功时被置为 PENDING;
 *       其余任务可以一直是 NULL。</li>
 *   <li>NULL 是合法存量值:历史任务早于 NOT_REQUIRED 存在,响应层统一归一化成 NOT_REQUIRED
 *       后再出网关,见 {@code AiTaskService#normalizeConfirmationStatus}。不要在实体层补默认值。</li>
 *   <li>PENDING 的任务不会被保留期清理压缩,因为用户还没在确认页处理完。</li>
 *   <li>CONFIRMED / REJECTED 都是一次性的终态,重复确认靠"已是 CONFIRMED 就返回原版本"实现幂等。</li>
 * </ul>
 */
public enum ConfirmationStatus {
    NOT_REQUIRED,
    PENDING,
    CONFIRMED,
    REJECTED
}
