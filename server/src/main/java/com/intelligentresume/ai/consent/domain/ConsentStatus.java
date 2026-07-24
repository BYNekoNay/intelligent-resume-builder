package com.intelligentresume.ai.consent.domain;

/**
 * AI 数据处理同意事件类型。
 *
 * <p>对应 DDL {@code ai_consent.event_type} 列。
 * 事件溯源模型:仅追加,不修改历史记录。
 */
public enum ConsentStatus {
    GRANTED,
    WITHDRAWN
}
