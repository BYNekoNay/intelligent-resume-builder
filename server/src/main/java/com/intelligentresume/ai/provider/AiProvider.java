package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

/**
 * AI 提供者接口。不同实现(mock、百炼等)通过 {@link #code()} 区分。
 */
public interface AiProvider {

    /** 提供者编码,如 "mock"、"bailian"。 */
    String code();

    /** 当前提供者使用的模型编码,用于审计记录。 */
    String modelCode();

    /** Whether this provider is configured for live calls. */
    default boolean isAvailable() {
        return true;
    }

    /** 是否支持指定任务类型。 */
    boolean supports(AiTaskType type);

    /** 执行 AI 调用。 */
    AiCallResult call(AiCallContext ctx);
}
