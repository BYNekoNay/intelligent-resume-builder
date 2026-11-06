package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

/**
 * AI 提供者接口 —— "怎么调一个模型服务"的统一约定，不同实现(mock、百炼等)通过 {@link #code()} 区分。
 *
 * <p>【链路位置】实现类（当前只有百炼）注册进 {@code AiProviderRegistry}，
 * 由 {@code TaskExecutionService} 经注册表路由后调用。</p>
 *
 * <p>【改坏它会怎样】实现若在未配密钥时抛异常而不是返回失败结果，会把 worker 线程打崩、
 * 任务状态没法落库。</p>
 *
 * <p>要点:</p>
 * <ul>
 *   <li>{@link #supports} 决定路由命中哪个实现;当前百炼实现对所有任务类型返回 true。</li>
 *   <li>未配置密钥时 {@link #call} 应返回失败结果而不是抛异常,这样调用方能把失败写进任务状态。</li>
 *   <li>{@link #availableModelCount()} 的两个默认实现只在单模型提供者上成立:
 *       多模型实现必须覆写,否则"全部模型冷却"会被健康检查误判为可用。</li>
 * </ul>
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
