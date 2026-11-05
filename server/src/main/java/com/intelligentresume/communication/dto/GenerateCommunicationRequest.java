package com.intelligentresume.communication.dto;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import jakarta.validation.constraints.NotNull;
/**
 * 生成沟通文案的请求（模板生成、AI 生成、AI 预览三处共用同一结构）。
 *
 * <p>要点：
 * <ul>
 *   <li>三个 ID/类型必填；{@code outputLanguage} 可空，统一由 {@link #normalizedLanguage()} 兜底，
 *       不要在别处再写一次 {@code language == null ? ZH_CN : language}。</li>
 *   <li>字段会被 {@code CommunicationAiPromptBuilder#buildTaskInput} 翻译成 AI 任务 input
 *       （{@code type} / {@code outputLanguage} 等），worker 依赖这些 key 重建 prompt，改名要同步两边。</li>
 * </ul>
 */
public record GenerateCommunicationRequest(@NotNull Long resumeVersionId, @NotNull Long jobDescriptionId,
                                           @NotNull CommunicationType type,
                                           CommunicationOutputLanguage outputLanguage) {
    public CommunicationOutputLanguage normalizedLanguage() {
        return outputLanguage == null ? CommunicationOutputLanguage.ZH_CN : outputLanguage;
    }
}
