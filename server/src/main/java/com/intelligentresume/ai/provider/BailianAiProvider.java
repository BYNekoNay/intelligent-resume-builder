package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云百炼(DashScope)AI 提供者。使用 OpenAI 兼容格式调用。
 *
 * <p>这是应用唯一的 AI 提供者。配置项位于 {@code app.ai.bailian.*}。
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
    private final String model;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public BailianAiProvider(
            @Value("${app.ai.bailian.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.ai.bailian.api-key:}") String apiKey,
            @Value("${app.ai.bailian.model:qwen-plus}") String model,
            @Value("${app.ai.bailian.connect-timeout-seconds:10}") int connectTimeout,
            @Value("${app.ai.bailian.read-timeout-seconds:60}") int readTimeout,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        log.info("BailianAiProvider initialized: baseUrl={}, model={}, connectTimeout={}s, readTimeout={}s",
                baseUrl, model, connectTimeout, readTimeout);
    }

    @Override
    public String code() {
        return "bailian";
    }

    @Override
    public boolean supports(AiTaskType type) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiCallResult call(AiCallContext ctx) {
        String requestId = UUID.randomUUID().toString();

        if (apiKey == null || apiKey.isBlank()) {
            return AiCallResult.fail("百炼 API Key 未配置 (app.ai.bailian.api-key)", false, requestId);
        }

        try {
            List<Map<String, String>> messages = buildMessages(ctx);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("response_format", Map.of("type", "json_object"));

            log.debug("Calling Bailian API: model={}, taskType={}, messagesCount={}",
                    model, ctx.type(), messages.size());

            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return AiCallResult.fail("百炼 API 返回空响应", true, requestId);
            }

            // 提取 provider request id
            String apiRequestId = response.containsKey("id")
                    ? String.valueOf(response.get("id")) : requestId;

            // 提取 choices[0].message.content
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return AiCallResult.fail("百炼 API 返回空 choices", true, apiRequestId);
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return AiCallResult.fail("百炼 API 返回空 message content", true, apiRequestId);
            }

            String content = (String) message.get("content");
            Map<String, Object> data = parseResponseContent(content, ctx.type());

            log.debug("Bailian API call success: taskType={}, requestId={}", ctx.type(), apiRequestId);
            return AiCallResult.ok(data, apiRequestId);

        } catch (ResourceAccessException e) {
            log.error("Bailian API timeout/connection error for taskType={}: {}",
                    ctx.type(), e.getMessage());
            return AiCallResult.fail("百炼 API 网络超时: " + e.getMessage(), true, requestId);
        } catch (Exception e) {
            log.error("Bailian API call failed for taskType={}: {}", ctx.type(), e.getMessage(), e);
            boolean retryable = isRetryableError(e);
            return AiCallResult.fail("百炼 API 调用失败: " + e.getMessage(), retryable, requestId);
        }
    }

    /**
     * 构建消息列表。JOB_GENERATION 使用上游传入的三段式 prompt;
     * 其他任务类型使用 PromptTemplates 构建。
     */
    private List<Map<String, String>> buildMessages(AiCallContext ctx) {
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

    private boolean isRetryableError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return true;
        // 429 Too Many Requests / 5xx 服务端错误 → 可重试
        return msg.contains("429") || msg.contains("500")
                || msg.contains("502") || msg.contains("503") || msg.contains("504");
    }
}
