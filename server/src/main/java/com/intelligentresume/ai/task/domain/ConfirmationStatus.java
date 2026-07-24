package com.intelligentresume.ai.task.domain;

/**
 * AI 任务结果确认状态。对应 DDL {@code ai_task.confirmation_status} 列(可为 NULL)。
 */
public enum ConfirmationStatus {
    NOT_REQUIRED,
    PENDING,
    CONFIRMED,
    REJECTED
}
