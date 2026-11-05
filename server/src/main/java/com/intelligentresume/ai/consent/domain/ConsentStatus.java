package com.intelligentresume.ai.consent.domain;

/**
 * AI 数据处理同意事件类型。
 *
 * <p>对应 DDL {@code ai_consent.event_type} 列。
 * 事件溯源模型:仅追加,不修改历史记录。
 *
 * <p>要点:
 * <ul>
 *   <li>只有两个值:授权与撤回。没有"过期"状态,失效靠政策版本比对或再追加一条 WITHDRAWN。</li>
 *   <li>判定"当前是否已授权"= 最新一条事件是不是 GRANTED,不看历史条数。</li>
 *   <li>名字以字符串落库,重命名等于改历史数据。</li>
 * </ul>
 */
public enum ConsentStatus {
    GRANTED,
    WITHDRAWN
}
