package com.intelligentresume.ai.task.domain;

/**
 * AI 任务状态。对应 DDL {@code ai_task.status} 列。
 */
public enum AiTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
