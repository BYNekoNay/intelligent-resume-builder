package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.provider.AiCallResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务结果的补充字段（R33）—— 把"这次调用内部发生过什么"（换过哪些模型）写进结果给用户看。
 *
 * <p>【业务问题】模型链降级（一个模型失败换下一个）以前只写日志，用户只能看到"成功了"或"失败了"，
 * 看不到背后换过几个模型。R33 要求"换可以换，但不能静默"。</p>
 *
 * <p>【链路位置】{@code TaskExecutionService#executeDefault} 在调用成功后把
 * {@code AiCallResult.modelAttempts} 补进任务结果；随结果一起落库并出网关。</p>
 *
 * <p>【改坏它会怎样】无——纯结果整形。复制逻辑改错会让 provider 返回的原始 map 被就地修改。</p>
 *
 * <p>抽成独立类有两个原因：{@code TaskExecutionService} 踩着行数棘轮（只许下调），
 * 而这些逻辑与"调度"无关 —— 它只是结果整形，单独放还能直接单测。</p>
 */
final class AiTaskResults {

    private AiTaskResults() {
    }

    /**
     * 把「这次顺延过哪些模型」写进任务结果，而不是只留一行日志。
     *
     * <p>常规单模型成功不写（避免给结果增加噪音）；只有真的发生过失败尝试才记录。
     *
     * <p>没有尝试轨迹时直接返回原 map（不复制）；有轨迹时复制一份再追加，
     * 避免改到 provider 返回的原始数据。
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
