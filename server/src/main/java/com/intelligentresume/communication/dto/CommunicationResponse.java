package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.CommunicationGenerationSource;

/**
 * 沟通文案响应（模板生成与草稿保存共用）。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code sentAutomatically} 恒为 false、{@code requiresManualConfirmation} 恒为 true —— 这是产品红线，
 *       不是占位字段，不要为了"自动化"把其中一个改成 true。</li>
 *   <li>{@code generationSource} 区分模板拼接与模型产出，前端据此决定是否展示"AI 生成"标识。</li>
 *   <li>本响应不含草稿 ID：需要回查/引用时走 AI 任务结果里的 {@code communicationDraftId}。</li>
 * </ul>
 */
public record CommunicationResponse(CommunicationType type, String draft, boolean sentAutomatically,
                                    boolean requiresManualConfirmation,
                                    CommunicationGenerationSource generationSource) {}
