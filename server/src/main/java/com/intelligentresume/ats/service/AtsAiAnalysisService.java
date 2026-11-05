package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ATS 的 AI 分析执行体（worker 侧调用）：取任务输入 → 调 provider → 校验 → 组装结果 JSON。
 *
 * <p>要点：
 * <ul>
 *   <li>修复重试只有一次，且只在 {@code INVALID_RESPONSE} 时触发；provider 超时/报错不再重试
 *       （那是重试整个任务的语义，不是修格式）。</li>
 *   <li>修复请求会把上一轮原始输出与错误信息一起回喂模型，属于"让模型照着格式改"，
 *       不是重新分析。</li>
 *   <li>结果里的 {@code promptVersion} / {@code schemaVersion} 取 {@code promptBuilder} 的当前值，
 *       不取任务快照里的副本 —— 改了配置后不能写出"记录说 v1、实际按 v2 跑"的假账。</li>
 *   <li>任务输入缺失（{@code inputSnapshotJson.input} 不是 Map）按 {@code INVALID_RESPONSE} 处理，
 *       这是数据损坏，重试也没用（{@code retryable=false}）。</li>
 * </ul>
 */
@Service
public class AtsAiAnalysisService {
    private final AiProviderRegistry providerRegistry;
    private final AtsAiPromptBuilder promptBuilder;
    private final AtsAiResultValidator validator;
    private final ObjectMapper objectMapper;

    public AtsAiAnalysisService(AiProviderRegistry providerRegistry, AtsAiPromptBuilder promptBuilder,
                                AtsAiResultValidator validator, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用链：取输入 → provider 首次调用 → 校验；校验失败且是 INVALID_RESPONSE 才修复重试一次
     * （第二次仍失败就直接把异常抛出去，不无限重试）。
     */
    public AnalysisResult analyze(AiTask task) {
        Map<String, Object> input = taskInput(task);
        AiProvider provider = providerRegistry.route(AiTaskType.ATS_ANALYSIS);
        AiCallResult response = call(provider, promptBuilder.build(input));
        AtsAiInsights insights;
        try {
            insights = validator.validate(response.data(), input.get("resumeJson"));
        } catch (AtsAiAnalysisException firstError) {
            if (firstError.fallbackCode() != AtsFallbackCode.INVALID_RESPONSE) throw firstError;
            response = call(provider, promptBuilder.buildRepair(input, response.data(), firstError.getMessage()));
            insights = validator.validate(response.data(), input.get("resumeJson"));
        }
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("atsCheckResultId", input.get("atsCheckResultId"));
        taskResult.put("aiInsights", objectMapper.convertValue(insights, Map.class));
        taskResult.put("providerRequestId", response.providerRequestId());
        // #85：记录「实际用于构建请求」的版本（prompt builder 是版本的唯一所有者），
        // 而不是任务输入快照里由创建方写入的副本——两者曾因默认值不同而漂移。
        taskResult.put("promptVersion", promptBuilder.promptVersion());
        taskResult.put("schemaVersion", promptBuilder.schemaVersion());
        return new AnalysisResult(insights, taskResult);
    }

    /**
     * provider 失败 → 转成带兜底码的异常。超时判定是**字符串匹配**
     * （{@code timeout} / {@code timed out} / {@code 超时}）：provider 改了报错文案就会退化成
     * {@code PROVIDER_ERROR}，只影响落库的兜底码/前端文案；{@code retryable} 另取
     * {@code response.retryable()}，与本次判定无关。
     */
    private AiCallResult call(AiProvider provider, Map<String, Object> prompt) {
        AiCallResult response = provider.call(new AiCallContext(AiTaskType.ATS_ANALYSIS, prompt));
        if (response.success()) return response;
        String message = response.errorMessage() == null ? "AI ATS analysis failed" : response.errorMessage();
        String normalized = message.toLowerCase();
        AtsFallbackCode code = normalized.contains("timeout") || normalized.contains("timed out") || message.contains("超时")
                ? AtsFallbackCode.PROVIDER_TIMEOUT : AtsFallbackCode.PROVIDER_ERROR;
        throw new AtsAiAnalysisException(code, message, response.retryable());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskInput(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (snapshot != null && snapshot.get("input") instanceof Map<?, ?> input) {
            return (Map<String, Object>) input;
        }
        throw new AtsAiAnalysisException(AtsFallbackCode.INVALID_RESPONSE, "ATS task input is missing", false);
    }

    public record AnalysisResult(AtsAiInsights insights, Map<String, Object> taskResult) {
    }
}
