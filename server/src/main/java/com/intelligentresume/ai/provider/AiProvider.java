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

    /**
     * 当前可用于调度的模型/端点数量。
     *
     * <p>单模型提供者返回 1（已配置）或 0（未配置）；多模型提供者（如百炼模型链）
     * 返回链上<b>当前未处于冷却期</b>的模型数。健康检查据此判断 AI 能力是否真的可用 ——
     * 只看 {@link #isAvailable()}（是否配了密钥）会漏掉「密钥有效但所有模型额度耗尽」的情况。
     */
    default int availableModelCount() {
        return isAvailable() ? 1 : 0;
    }

    /** 是否支持指定任务类型。 */
    boolean supports(AiTaskType type);

    /** 执行 AI 调用。 */
    AiCallResult call(AiCallContext ctx);
}
