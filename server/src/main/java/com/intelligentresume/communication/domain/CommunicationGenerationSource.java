package com.intelligentresume.communication.domain;

/**
 * 文案来源：模板拼接（{@code TEMPLATE}）或模型生成（{@code AI}）。
 *
 * <p>用于让前端/审计区分「确定性文案」与「模型产出」；它<b>不是</b>发送状态，
 * 两种来源都只产出草稿，从不自动发送。新增来源要同步响应字段的消费端。
 */
public enum CommunicationGenerationSource {
    TEMPLATE,
    AI
}
