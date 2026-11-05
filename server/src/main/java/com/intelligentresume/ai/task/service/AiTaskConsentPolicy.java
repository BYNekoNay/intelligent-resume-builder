package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.List;
import java.util.Map;

/**
 * 任务输入所需数据类别的统一取数口。
 *
 * <p>要点:
 * <ul>
 *   <li>同一份快照推导出的类别必须被用两次:任务落库前(创建时)和 provider 执行前(worker 里),
 *       两次口径必须一致,否则会出现"创建时通过、执行时被判越权"。</li>
 *   <li>规则本体在 {@link AiTaskCapabilityRegistry.Descriptor},本类只是转发;
 *       不要在调用方自己拼类别列表。</li>
 *   <li>领域端点把 JD 引用放在嵌套 {@code input} 里,JD 探测逻辑已经覆盖这种结构。</li>
 * </ul>
 */
public final class AiTaskConsentPolicy {

    private AiTaskConsentPolicy() {
    }

    public static List<String> requiredCategories(AiTaskType type, Map<String, Object> inputSnapshot) {
        return AiTaskCapabilityRegistry.requireRegistered(type)
                .requiredConsentCategories(inputSnapshot);
    }
}
