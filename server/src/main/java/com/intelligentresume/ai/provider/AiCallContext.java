package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.Map;

/**
 * AI 调用上下文。
 */
public record AiCallContext(
        AiTaskType type,
        Map<String, Object> input,
        long timeoutMs
) {
}
