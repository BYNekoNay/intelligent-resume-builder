package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.provider.AiCallResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务结果的补充字段（R33）。
 *
 * <p>抽成独立类有两个原因：{@code TaskExecutionService} 踩着行数棘轮（只许下调），
 * 而这些逻辑与"调度"无关 —— 它只是结果整形，单独放还能直接单测。
 */
final class AiTaskResults {

    private AiTaskResults() {
    }

    /**
     * 把「这次顺延过哪些模型」写进任务结果，而不是只留一行日志。
     *
     * <p>常规单模型成功不写（避免给结果增加噪音）；只有真的发生过失败尝试才记录。
     */
    static Map<String, Object> withModelAttempts(Map<String, Object> taskResult, AiCallResult result) {
        if (result.modelAttempts() == null || result.modelAttempts().isEmpty() || taskResult == null) {
            return taskResult;
        }
        Map<String, Object> copy = new LinkedHashMap<>(taskResult);
        copy.put("modelAttempts", result.modelAttempts());
        return copy;
    }
}
