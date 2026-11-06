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
import com.intelligentresume.common.observability.AiFailureCategory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ATS 的 AI 分析执行体：后台 worker 领到任务后，真正"调模型干活"的就是这个类。
 *
 * <h2>① 解决什么业务问题</h2>
 * 前面 {@code AtsAiStarter} 只是"下单"，本类是"后厨"：拿任务里存的简历+JD+规则结果，
 * 按 prompt 模板发给模型，把模型返回的自由文本校验、翻译成系统能存的结构化结论。
 *
 * <h2>② 在链路哪里</h2>
 * ai.task 模块的 worker 调它 → 它调 {@link AtsAiPromptBuilder} 组装 prompt、
 * 调 ai.provider 模块发请求、调 {@link AtsAiResultValidator} 校验返回；
 * 产出 {@link AnalysisResult} 交给 worker，由 worker 经 {@code AtsResultStateService} 写回检查记录。
 *
 * <h2>③ 改坏它用户会看到什么</h2>
 * 修复重试改成无限重试 → 一条坏任务会反复烧模型配额；
 * 校验环节跳过 → 模型编造的引文/多余的分数字段直接进库，界面上出现"简历里没有的话"；
 * 版本号改取任务快照 → 数据库里出现"记录说 v1、实际按 v2 跑"的对不上账。
 *
 * <p>原有要点（保留）：
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
     * 一次完整分析。步骤：
     * ① 从任务快照里取出创建时存好的输入（简历 JSON、JD、规则结果）；
     * ② 用 prompt 模板组装出要发给模型的消息，首次调用模型；
     * ③ 校验返回是否符合约定结构（字段白名单、条数上限、引文真实性）；
     * ④ 校验失败且原因是"格式不对"（INVALID_RESPONSE）时，把上次的错误输出回喂模型修一次格式——
     *    最多修一次，第二次仍失败直接抛异常，绝不无限重试；
     * ⑤ 把结论连同 prompt/schema 版本号打包成结果，由 worker 写回检查记录。
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
     * provider 失败 → 转成带兜底码的异常。超时判定**优先用结构化信号**（#67）：
     * 真实超时路径（BailianAiProvider 捕获 {@code ResourceAccessException}）的 errorMessage
     * 是「百炼 API 网络异常」，不含 timeout 文案，但 attempts 的 {@code failureCategory}
     * 固定为 {@code AiFailureCategory.TIMEOUT}；只有拿不到结构化类别时才退回文案匹配
     * （{@code timeout} / {@code timed out} / {@code 超时}）——文案匹配是会随上游措辞漂移的兜底。
     * {@code retryable} 另取 {@code response.retryable()}，与本次判定无关。
     */
    private AiCallResult call(AiProvider provider, Map<String, Object> prompt) {
        AiCallResult response = provider.call(new AiCallContext(AiTaskType.ATS_ANALYSIS, prompt));
        if (response.success()) return response;
        String message = response.errorMessage() == null ? "AI ATS analysis failed" : response.errorMessage();
        String normalized = message.toLowerCase();
        boolean structuredTimeout = response.modelAttempts().stream()
                .anyMatch(attempt -> AiFailureCategory.TIMEOUT.name().equals(attempt.failureCategory()));
        boolean messageTimeout = normalized.contains("timeout") || normalized.contains("timed out")
                || message.contains("超时");
        AtsFallbackCode code = structuredTimeout || messageTimeout
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
