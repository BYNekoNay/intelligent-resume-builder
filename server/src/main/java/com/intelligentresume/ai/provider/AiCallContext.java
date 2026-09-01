package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.Map;

/**
 * AI 调用上下文。
 *
 * <p>超时由唯一 provider 的全局配置控制（app.ai.bailian.read-timeout-seconds），
 * 不在上下文携带 per-call 超时，避免误导调用方以为存在独立超时窗口。
 */
public record AiCallContext(
        AiTaskType type,
        Map<String, Object> input
) {
}
