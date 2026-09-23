package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.observability.AiFailureCategory;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云百炼(DashScope)AI 提供者。使用 OpenAI 兼容格式调用。
 *
 * <p>这是应用唯一的 AI 提供者。配置项位于 {@code app.ai.bailian.*}。
 *
 * <h2>模型链</h2>
 * 百炼的免费额度是<b>按模型</b>计量的。历史上这里硬编码单个模型，
 * 该模型额度一旦耗尽，全部 AI 功能同时失败且无法自愈。
 * 现在改为按 {@code app.ai.bailian.model-chain} 配置的有序模型链调度：
 *
 * <ol>
 *   <li>按链序取当前未处于冷却期的候选；</li>
 *   <li>逐个尝试，首个成功即返回；</li>
 *   <li>失败按 {@link BailianFailureClassifier} 判定顺延还是终止 ——
 *       额度耗尽 / 模型下线顺延并长冷却，瞬时故障顺延并短冷却，
 *       密钥无效等「换模型也没用」的情况立即终止；</li>
 *   <li>全部候选都不可用时快速失败，由 worker 的既有重试机制兜底。</li>
 * </ol>
 *
 * <p>当 {@code model-chain} 为空时退化为单模型（取 {@code model}），行为与改造前一致。
 *
 * <h2>隐私边界</h2>
 * 日志与指标只记录模型名、失败分类、HTTP 状态与请求标识；
 * 不记录 prompt、模型原文、响应体（4xx 响应体可能回显简历/JD 片段）。
 *
 * <p>对于 JOB_GENERATION 任务,使用上游 JobGenerationService 构建的三段式 prompt;
 * 对于其他任务类型,使用内置 PromptTemplates 构建 prompt。
 */
@Component
public class BailianAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(BailianAiProvider.class);
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[[\\s\\S]*]");

    private final RestClient restClient;
    private final String apiKey;
    private final ModelChainState chain;
    private final Duration chainTotalBudget;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AppObservability observability;
    private final FailureCategoryClassifier failureCategoryClassifier;

    /**
     * 单调时钟，用于链总预算判定。抽成字段便于单测注入，避免测试里真的等待。
     */
    private LongSupplier nanoTime = System::nanoTime;

    public BailianAiProvider(
            @Value("${app.ai.bailian.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.ai.bailian.api-key:}") String apiKey,
            @Value("${app.ai.bailian.model:qwen-plus}") String model,
            @Value("${app.ai.bailian.model-chain:}") String modelChain,
            @Value("${app.ai.bailian.connect-timeout-seconds:10}") int connectTimeout,
            @Value("${app.ai.bailian.read-timeout-seconds:60}") int readTimeout,
            @Value("${app.ai.bailian.chain-quota-cooldown-seconds:1800}") long quotaCooldownSeconds,
            @Value("${app.ai.bailian.chain-transient-cooldown-seconds:60}") long transientCooldownSeconds,
            @Value("${app.ai.bailian.chain-total-budget-seconds:600}") long chainTotalBudgetSeconds,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            AppObservability observability,
            FailureCategoryClassifier failureCategoryClassifier) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.observability = observability;
        this.failureCategoryClassifier = failureCategoryClassifier;
        this.chain = new ModelChainState(
                parseChain(modelChain, model),
                Duration.ofSeconds(quotaCooldownSeconds),
                Duration.ofSeconds(transientCooldownSeconds),
                Clock.systemUTC());
        this.chainTotalBudget = Duration.ofSeconds(Math.max(chainTotalBudgetSeconds, readTimeout));

        // 使用 JDK HttpClient 请求工厂：HttpURLConnection 默认强制 Accept-Encoding: gzip，
        // 百炼对 gzip 响应会以 application/octet-stream 返回导致 RestClient 无法反序列化；
        // JDK HttpClient 默认不发送 gzip 头，配合 Accept-Encoding: identity 双保险避免压缩。
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeout)).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        if (observability != null) {
            observability.registerModelChainAvailabilityGauge(this::availableModelCount);
        }

        log.info("BailianAiProvider initialized: baseUrl={}, modelChain={}, connectTimeout={}s, readTimeout={}s, "
                        + "quotaCooldown={}s, transientCooldown={}s, chainTotalBudget={}s",
                baseUrl, chain.models(), connectTimeout, readTimeout, quotaCooldownSeconds, transientCooldownSeconds,
                chainTotalBudget.toSeconds());
    }

    /**
     * 解析模型链配置。链为空或去重后为空时退化为单模型，保证向后兼容。
     */
    private static List<String> parseChain(String modelChain, String fallbackModel) {
        List<String> parsed = new ArrayList<>();
        if (modelChain != null) {
            for (String candidate : modelChain.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty() && !parsed.contains(trimmed)) {
                    parsed.add(trimmed);
                }
            }
        }
        if (parsed.isEmpty() && fallbackModel != null && !fallbackModel.isBlank()) {
            parsed.add(fallbackModel.trim());
        }
        return parsed;
    }

    @Override
    public String code() {
        return "bailian";
    }

    /** 审计字段：链首模型。实际服务模型见调用日志与 {@code resume_ai_provider_calls} 指标。 */
    @Override
    public String modelCode() {
        return chain.primary();
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 当前可调度的模型数量：链上未处于冷却期的模型数。 */
    @Override
    public int availableModelCount() {
        return isAvailable() ? chain.availableCount() : 0;
    }

    /** 供健康检查与排障读取模型链快照，不含任何敏感信息。 */
    public List<String> modelChain() {
        return chain.models();
    }

    /** 测试用：替换单调时钟，用于验证链总预算而无需在测试里真实等待。 */
    void useNanoTimeSupplier(LongSupplier supplier) {
        this.nanoTime = Objects.requireNonNull(supplier);
    }

    @Override
    public boolean supports(AiTaskType type) {
        return true;
    }

    /** 单次模型调用的结果、失败分类与调度动作。 */
    record ModelOutcome(AiCallResult result,
                        AiFailureCategory category,
                        BailianFailureClassifier.Disposition disposition,
                        String errorCode) {
    }

    /** 对单个模型执行一次调用；抽出接口以便对链式调度做无网络单测。 */
    @FunctionalInterface
    interface ModelInvoker {
        ModelOutcome invoke(String model);
    }

    @Override
    public AiCallResult call(AiCallContext ctx) {
        String requestId = UUID.randomUUID().toString();

        if (apiKey == null || apiKey.isBlank()) {
            AiCallResult result = AiCallResult.fail("百炼 API Key 未配置 (app.ai.bailian.api-key)", false, requestId);
            observability.recordAiProviderCall(ctx.type(), code(), modelCode(), false,
                    AiFailureCategory.PROVIDER_4XX, Duration.ZERO);
            return result;
        }

        return callChain(ctx, requestId, model -> invokeModelOnce(model, ctx, requestId));
    }

    /**
     * 链式调度：按候选顺序逐个尝试，首个成功即返回。
     *
     * <p>包级可见，便于单测注入伪调用而不触碰网络。
     *
     * @param ctx       调用上下文
     * @param requestId 本次调用标识，用于排障关联
     * @param invoker   单模型调用实现
     * @return 成功结果，或全部候选失败后的聚合失败结果
     */
    AiCallResult callChain(AiCallContext ctx, String requestId, ModelInvoker invoker) {
        List<String> candidates = chain.candidates();
        if (candidates.isEmpty()) {
            Instant availableAgainAt = chain.availableAgainAt();
            log.warn("Model chain has no schedulable candidate: taskType={}, chainSize={}, earliestRecovery={}",
                    ctx.type(), chain.size(), availableAgainAt);
            return AiCallResult.fail(
                    "百炼模型链全部处于冷却期（额度耗尽或持续故障），暂时无法调度。最早恢复时间：" + availableAgainAt,
                    true, requestId);
        }

        AiFailureCategory lastCategory = AiFailureCategory.NONE;
        String lastMessage = null;
        boolean sawQuotaExhausted = false;
        boolean budgetExhausted = false;
        int attempted = 0;
        long chainStartedAt = nanoTime.getAsLong();

        for (int index = 0; index < candidates.size(); index++) {
            // 链总预算：单个模型的读超时（默认 300s）乘以链长度会放大成数十分钟，
            // 而 worker 单条任务会一直占住线程，导致后续 AI 任务排队阻塞。
            // 超出预算即停止顺延并快速失败，交由 worker 的既有重试机制后续再跑。
            if (index > 0 && elapsedSecondsSince(chainStartedAt) >= chainTotalBudget.toSeconds()) {
                budgetExhausted = true;
                log.warn("Model chain total budget exhausted, stopping fallback: taskType={}, attempted={}, "
                        + "budget={}s", ctx.type(), attempted, chainTotalBudget.toSeconds());
                break;
            }

            String model = candidates.get(index);
            ModelOutcome outcome = invoker.invoke(model);
            attempted++;

            if (outcome.result().success()) {
                if (index > 0) {
                    log.info("Model chain succeeded after fallback: taskType={}, model={}, fallbackDepth={}",
                            ctx.type(), model, index);
                }
                chain.markSuccess(model);
                return outcome.result();
            }

            lastCategory = outcome.category();
            lastMessage = outcome.result().errorMessage();
            if (outcome.category() == AiFailureCategory.QUOTA_EXHAUSTED
                    || BailianFailureClassifier.isQuotaExhausted(outcome.errorCode())) {
                sawQuotaExhausted = true;
            }

            if (outcome.disposition() == BailianFailureClassifier.Disposition.ABORT) {
                // 换模型也无济于事（如密钥无效、请求参数非法），不浪费剩余模型的额度与时间
                log.warn("Model chain aborted without fallback: taskType={}, model={}, category={}",
                        ctx.type(), model, outcome.category());
                return outcome.result();
            }

            if (outcome.disposition() == BailianFailureClassifier.Disposition.NEXT_MODEL_LONG_COOLDOWN) {
                chain.markQuotaExhausted(model);
            } else {
                chain.markTransientFailure(model);
            }

            if (index + 1 < candidates.size()) {
                String nextModel = candidates.get(index + 1);
                observability.recordModelChainFallback(ctx.type(), model, nextModel);
                log.warn("Model chain falling back: taskType={}, from={}, to={}, category={}",
                        ctx.type(), model, nextModel, outcome.category());
            }
        }

        AiFailureCategory aggregateCategory = sawQuotaExhausted
                ? AiFailureCategory.QUOTA_EXHAUSTED : lastCategory;
        log.warn("Model chain exhausted: taskType={}, attempted={}, budgetExhausted={}, aggregateCategory={}, "
                        + "availableAfter={}",
                ctx.type(), attempted, budgetExhausted, aggregateCategory, chain.availableCount());
        String message = budgetExhausted
                ? "百炼模型链已达总时间预算（已尝试 " + attempted + " 个模型，预算 "
                        + chainTotalBudget.toSeconds() + "s）；" + describeLastMessage(lastMessage)
                : buildAggregateMessage(attempted, lastMessage);
        return AiCallResult.fail(message, true, requestId);
    }

    private long elapsedSecondsSince(long startedAtNanos) {
        return Duration.ofNanos(nanoTime.getAsLong() - startedAtNanos).toSeconds();
    }

    private String describeLastMessage(String lastMessage) {
        return lastMessage == null || lastMessage.isBlank() ? "无末次错误信息" : "末次失败：" + lastMessage;
    }

    private String buildAggregateMessage(int attempted, String lastMessage) {
        StringBuilder message = new StringBuilder("百炼模型链全部失败（已尝试 ")
                .append(attempted)
                .append(" 个模型）");
        if (lastMessage != null && !lastMessage.isBlank()) {
            message.append("；末次失败：").append(lastMessage);
        }
        return message.toString();
    }

    /**
     * 对单个模型执行一次真实调用，并把结果、分类与调度动作一并返回。
     *
     * <p>每次尝试都会记录一条模型维度的指标，因此仪表盘上可以区分
     * 「哪个模型在服务」与「哪个模型在失败」。
     */
    @SuppressWarnings("unchecked")
    private ModelOutcome invokeModelOnce(String model, AiCallContext ctx, String requestId) {
        long startedAt = System.nanoTime();

        try {
            List<Map<String, String>> messages = buildMessages(ctx);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature",
                    ctx.type() == AiTaskType.INTERVIEW_COACH || ctx.type() == AiTaskType.ATS_ANALYSIS ? 0.1 : 0.7);
            requestBody.put("response_format", Map.of("type", "json_object"));

            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header(TraceIdFilter.TRACE_ID_HEADER, traceId())
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return failed(ctx, model, requestId, startedAt,
                        AiCallResult.fail("百炼 API 返回空响应", true, requestId),
                        AiFailureCategory.PROVIDER_RESPONSE_INVALID,
                        BailianFailureClassifier.dispositionForCategory(AiFailureCategory.PROVIDER_RESPONSE_INVALID));
            }

            String apiRequestId = response.containsKey("id")
                    ? String.valueOf(response.get("id")) : requestId;

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return failed(ctx, model, apiRequestId, startedAt,
                        AiCallResult.fail("百炼 API 返回空 choices", true, apiRequestId),
                        AiFailureCategory.PROVIDER_RESPONSE_INVALID,
                        BailianFailureClassifier.dispositionForCategory(AiFailureCategory.PROVIDER_RESPONSE_INVALID));
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return failed(ctx, model, apiRequestId, startedAt,
                        AiCallResult.fail("百炼 API 返回空 message content", true, apiRequestId),
                        AiFailureCategory.PROVIDER_RESPONSE_INVALID,
                        BailianFailureClassifier.dispositionForCategory(AiFailureCategory.PROVIDER_RESPONSE_INVALID));
            }

            String content = (String) message.get("content");
            Map<String, Object> data = parseResponseContent(content, ctx.type());

            return succeeded(ctx, model, startedAt, AiCallResult.ok(data, apiRequestId));

        } catch (ResourceAccessException e) {
            AiFailureCategory category = failureCategoryClassifier.ai(e);
            log.warn("Bailian API transport failure: taskType={}, model={}, category={}, exception={}",
                    ctx.type(), model, category, e.getClass().getSimpleName());
            return failed(ctx, model, requestId, startedAt,
                    AiCallResult.fail("百炼 API 网络异常", true, requestId), category,
                    BailianFailureClassifier.dispositionForCategory(category));
        } catch (RestClientResponseException e) {
            // 隐私约束：只提取机器可读错误码用于分类，绝不记录响应体（可能回显简历/JD 片段）。
            String errorCode = BailianFailureClassifier.extractErrorCode(safeBody(e));
            AiFailureCategory category = BailianFailureClassifier.categoryFor(e.getStatusCode().value(), errorCode);
            BailianFailureClassifier.Disposition disposition =
                    BailianFailureClassifier.dispositionFor(e.getStatusCode().value(), errorCode);
            log.warn("Bailian API response failure: taskType={}, model={}, category={}, status={}, "
                            + "errorCode={}, providerRequestId={}",
                    ctx.type(), model, category, e.getStatusCode().value(), errorCode, requestId);
            return failed(ctx, model, requestId, startedAt,
                    AiCallResult.fail("百炼 API 调用失败", disposition != BailianFailureClassifier.Disposition.ABORT,
                            requestId),
                    category, disposition);
        } catch (Exception e) {
            AiFailureCategory category = failureCategoryClassifier.ai(e);
            log.warn("Bailian API call failure: taskType={}, model={}, category={}, exception={}",
                    ctx.type(), model, category, e.getClass().getSimpleName());
            return failed(ctx, model, requestId, startedAt,
                    AiCallResult.fail("百炼 API 调用失败", BailianFailureClassifier.isChainable(category), requestId),
                    category, BailianFailureClassifier.dispositionForCategory(category));
        }
    }

    /** 读取 4xx 响应体用于提取错误码；内容不落日志、不入异常消息。 */
    private String safeBody(RestClientResponseException e) {
        try {
            return e.getResponseBodyAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ModelOutcome succeeded(AiCallContext ctx, String model, long startedAt, AiCallResult result) {
        observability.recordAiProviderCall(ctx.type(), code(), model, true,
                AiFailureCategory.NONE, Duration.ofNanos(System.nanoTime() - startedAt));
        log.info("AI provider call completed: taskType={}, model={}, outcome=success", ctx.type(), model);
        return new ModelOutcome(result, AiFailureCategory.NONE,
                BailianFailureClassifier.Disposition.SUCCEEDED, null);
    }

    private ModelOutcome failed(AiCallContext ctx, String model, String providerRequestId, long startedAt,
                                AiCallResult result, AiFailureCategory category,
                                BailianFailureClassifier.Disposition disposition) {
        observability.recordAiProviderCall(ctx.type(), code(), model, false, category,
                Duration.ofNanos(System.nanoTime() - startedAt));
        try (MDC.MDCCloseable ignored = MDC.putCloseable("providerRequestId", providerRequestId)) {
            log.info("AI provider call completed: taskType={}, model={}, outcome=failure, category={}",
                    ctx.type(), model, category);
        }
        return new ModelOutcome(result, category, disposition, null);
    }

    private String traceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    /**
     * 构建消息列表。JOB_GENERATION 使用上游传入的三段式 prompt;
     * 其他任务类型使用 PromptTemplates 构建。
     *
     * <p>包级可见以便单测直接断言 prompt 组装结果，无需发起真实网络调用。
     */
    List<Map<String, String>> buildMessages(AiCallContext ctx) {
        Map<String, Object> input = ctx.input() != null ? ctx.input() : Map.of();

        // 如果上游传入了构建好的 prompt(JOB_GENERATION 路径)
        String systemPrompt = (String) input.get("_systemPrompt");
        String taskPrompt = (String) input.get("_taskPrompt");
        String dataPrompt = (String) input.get("_dataPrompt");

        List<Map<String, String>> messages = new ArrayList<>();

        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
            String userContent = (taskPrompt != null ? taskPrompt + "\n\n" : "")
                    + (dataPrompt != null ? dataPrompt : "");
            messages.add(Map.of("role", "user", "content", userContent));
        } else {
            // 通用任务类型:使用内置模板
            String system = PromptTemplates.systemFor(ctx.type(), input);
            String userPrompt = PromptTemplates.userPromptFor(ctx.type(), input);
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", userPrompt));
        }

        return messages;
    }

    /**
     * 解析 LLM 返回内容。支持纯 JSON、markdown code block 包裹、
     * 以及文本中嵌入 JSON 的情况。
     *
     * <p>刻意保持既有语义：解析失败时返回 {@code rawContent} 而非失败，
     * 由下游的 schema 校验负责判定。模型链只负责「调用成功与否」，
     * 不在此处做输出质量判定（否则同一个坏 prompt 会在链上重复消耗额度）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponseContent(String content, AiTaskType taskType) {
        String json = extractJson(content);

        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            // 如果是数组,包装为对象
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("items", parsed);
            return wrapper;
        } catch (Exception e) {
            log.warn("Failed to parse AI response as JSON, wrapping as text. taskType={}", taskType);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("rawContent", content);
            fallback.put("parseError", e.getMessage());
            return fallback;
        }
    }

    /**
     * 从 LLM 输出中提取 JSON 字符串。
     * 优先级:markdown code block → 整体 JSON → 嵌入的 JSON 对象/数组。
     */
    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String trimmed = content.trim();

        // 1. 尝试提取 markdown code block 中的内容
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }

        // 2. 如果整体以 { 或 [ 开头,直接返回
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        // 3. 尝试找嵌入的 JSON 对象
        Matcher objMatcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (objMatcher.find()) {
            return objMatcher.group();
        }

        // 4. 尝试找嵌入的 JSON 数组
        Matcher arrMatcher = JSON_ARRAY_PATTERN.matcher(trimmed);
        if (arrMatcher.find()) {
            return arrMatcher.group();
        }

        // 5. 无法提取,返回原文(会在 parseResponseContent 中 fallback)
        return trimmed;
    }
}
