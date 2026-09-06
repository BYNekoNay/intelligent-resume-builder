package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 提供者注册表。根据任务类型路由到合适的提供者。
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
}
