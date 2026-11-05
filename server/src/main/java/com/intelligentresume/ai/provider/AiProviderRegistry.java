package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 提供者注册表 —— 按任务类型从 Spring 注入的 {@link AiProvider} 列表里挑一个来调用。
 *
 * <p>要点 / 易错点:
 * <ul>
 *   <li>路由是「第一个 supports() 返回 true 的实现」,顺序由 Spring 注入的 List 决定
 *       (可再受 {@code @Order}/{@code Ordered} 影响)。当前只有一个百炼实现且它对所有类型
 *       都返回 true,所以顺序暂时无关紧要;一旦加入第二个提供者,顺序就变成业务决策。</li>
 *   <li>路由**不看**是否已配置密钥:密钥缺失由 provider 在 call 时返回失败结果,
 *       或由调用方先查 {@link #hasAvailableProvider()}。这样缺少密钥只会让任务失败,
 *       不会把整个端点变成 500。</li>
 *   <li>两个健康检查口径不同:{@link #hasAvailableProvider()} 回答"配没配",
 *       {@link #availableModelCount()} 回答"现在能不能调"(模型链全部冷却时为 0)。</li>
 * </ul>
 */
@Component
public class AiProviderRegistry {

    private final List<AiProvider> providers;

    public AiProviderRegistry(List<AiProvider> providers) {
        this.providers = providers;
    }

    /**
     * 根据任务类型查找第一个支持的提供者。
     *
     * <p>不做可用性过滤：提供者是否已配置（{@link AiProvider#isAvailable()}）由
     * 调用方在路由后判定，或由提供者自身在 {@link AiProvider#call} 时优雅降级
     * （如未配置密钥时返回失败结果而非抛出 500）。仅当没有任何提供者支持该类型
     * 时才抛出异常。
     *
     * @throws BusinessException AI_FAILURE 如果没有提供者支持该类型
     */
    public AiProvider route(AiTaskType type) {
        return providers.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_FAILURE,
                        "没有可用的 AI 提供者支持任务类型: " + type));
    }

    /** 是否存在至少一个已配置（可用）的提供者，供系统健康检查使用。 */
    public boolean hasAvailableProvider() {
        return providers.stream().anyMatch(AiProvider::isAvailable);
    }

    /**
     * 全部提供者当前可调度的模型/端点总数，供系统健康检查使用。
     *
     * <p>与 {@link #hasAvailableProvider()} 的区别：后者只回答「配没配密钥」，
     * 本方法回答「现在真的能不能调」。百炼模型链在全部模型额度耗尽时会返回 0，
     * 此时 AI 能力实际不可用，健康检查应据此报 DEGRADED。
     */
    public int availableModelCount() {
        return providers.stream().mapToInt(AiProvider::availableModelCount).sum();
    }
}
