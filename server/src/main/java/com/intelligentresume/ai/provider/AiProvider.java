package com.intelligentresume.ai.provider;

import java.util.Map;

/**
 * AI 提供商接口。
 *
 * <p>骨架:T06 实现 Mock Provider;真实 LLM 由后续 ADR 单独接入。
 */
public interface AiProvider {

    /**
     * @param taskType    任务类型(JOB_GENERATION 等)
     * @param input       输入 JSON(不含敏感原文)
     * @return 输出 JSON 必须符合对应 schema
     */
    Map<String, Object> invoke(String taskType, Map<String, Object> input);

    String code();
}