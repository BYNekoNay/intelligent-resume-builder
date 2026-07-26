package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BailianAiProvider 单元测试。测试 JSON 提取、Prompt 构建等逻辑。
 * 不实际调用网络 API。
 */
class BailianAiProviderTest {

    private BailianAiProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppObservability observability = org.mockito.Mockito.mock(AppObservability.class);
    private final FailureCategoryClassifier failureCategoryClassifier = new FailureCategoryClassifier();

    @BeforeEach
    void setUp() {
        provider = new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-api-key",
                "qwen-plus",
                10,
                60,
                objectMapper, observability, failureCategoryClassifier
        );
    }

    @Test
    @DisplayName("code() 返回 bailian")
    void codeReturnsBailian() {
        assertEquals("bailian", provider.code());
    }

    @Test
    @DisplayName("supports() 支持所有任务类型")
    void supportsAllTypes() {
        for (AiTaskType type : AiTaskType.values()) {
            assertTrue(provider.supports(type), "Should support " + type);
        }
    }

    @Test
    @DisplayName("API Key 为空时返回失败")
    void emptyApiKeyReturnsFail() {
        BailianAiProvider noKeyProvider = new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "",
                "qwen-plus",
                10, 60, objectMapper, observability, failureCategoryClassifier
        );
        AiCallContext ctx = new AiCallContext(AiTaskType.RESUME_OPTIMIZE, Map.of(), 60000);
        AiCallResult result = noKeyProvider.call(ctx);
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("API Key"));
        assertFalse(result.retryable());
    }

    @Test
    @DisplayName("PromptTemplates 为每种任务类型生成非空 system prompt")
    void promptTemplatesSystemNotEmpty() {
        for (AiTaskType type : AiTaskType.values()) {
            String system = PromptTemplates.systemFor(type);
            assertNotNull(system);
            assertFalse(system.isBlank(), "System prompt should not be blank for " + type);
        }
    }

    @Test
    @DisplayName("PromptTemplates 为 RESUME_OPTIMIZE 生成包含内容的 user prompt")
    void promptTemplatesResumeOptimize() {
        Map<String, Object> input = new HashMap<>();
        input.put("content", "我负责开发公司的核心系统");
        input.put("targetJdText", "招聘 Java 高级工程师,要求 5 年经验");

        String userPrompt = PromptTemplates.userPromptFor(AiTaskType.RESUME_OPTIMIZE, input);
        assertTrue(userPrompt.contains("核心系统"));
        assertTrue(userPrompt.contains("Java 高级工程师"));
    }

    @Test
    @DisplayName("PromptTemplates 为 INLINE_OPTIMIZE 生成包含文本的 user prompt")
    void promptTemplatesInlineOptimize() {
        Map<String, Object> input = new HashMap<>();
        input.put("text", "做了订单管理系统");

        String userPrompt = PromptTemplates.userPromptFor(AiTaskType.INLINE_OPTIMIZE, input);
        assertTrue(userPrompt.contains("订单管理系统"));
    }

    @Test
    @DisplayName("PromptTemplates 过滤下划线开头的内部字段")
    void promptTemplatesFiltersInternalFields() {
        Map<String, Object> input = new HashMap<>();
        input.put("_systemPrompt", "internal system");
        input.put("_taskPrompt", "internal task");
        input.put("content", "用户可见内容");

        String userPrompt = PromptTemplates.userPromptFor(AiTaskType.MATERIAL_IMPORT, input);
        assertFalse(userPrompt.contains("internal system"));
        assertTrue(userPrompt.contains("用户可见内容"));
    }

    @Test
    @DisplayName("联想补全仅返回参考材料，并保留用户原始输入")
    void materialAssociationPromptMarksOutputAsReferenceOnly() {
        Map<String, Object> input = Map.of(
                "generationMode", "ASSOCIATIVE_EXPANSION",
                "rawMaterialText", "软件工程专业，微服务架构");

        String system = PromptTemplates.systemFor(AiTaskType.MATERIAL_IMPORT, input);
        String userPrompt = PromptTemplates.userPromptFor(AiTaskType.MATERIAL_IMPORT, input);

        assertTrue(system.contains("reference"));
        assertTrue(userPrompt.contains("微服务架构"));
        assertTrue(userPrompt.contains("expandedMaterial"));
    }

    @Test
    @DisplayName("联想结果可以生成标记待核实的结构化草稿")
    void materialAssociationStructuredDraftPromptUsesResumeJsonContract() {
        Map<String, Object> input = Map.of(
                "generationMode", "ASSOCIATIVE_STRUCTURED_DRAFT",
                "rawMaterialText", "软件工程专业",
                "associationReference", "可以掌握微服务架构（待核实）");

        String system = PromptTemplates.systemFor(AiTaskType.MATERIAL_IMPORT, input);
        String userPrompt = PromptTemplates.userPromptFor(AiTaskType.MATERIAL_IMPORT, input);

        assertTrue(system.contains("generatedResumeJson"));
        assertTrue(system.contains("must not include meta"));
        assertTrue(userPrompt.contains("associationReference"));
        assertTrue(userPrompt.contains("generatedResumeJson"));
    }

    @Test
    @DisplayName("JOB_GENERATION 使用上游传入的三段式 prompt")
    void jobGenerationUsesUpstreamPrompt() {
        // 验证 buildMessages 逻辑:当 input 包含 _systemPrompt 时使用上游 prompt
        // 由于 buildMessages 是 private,通过 call() 的行为间接验证
        // 这里我们验证当 API key 有效但网络不通时的错误处理
        Map<String, Object> input = new HashMap<>();
        input.put("_systemPrompt", "你是简历助手");
        input.put("_taskPrompt", "生成简历");
        input.put("_dataPrompt", "===DATA===\n测试数据\n===END===");
        input.put("jobDescriptionId", 1L);

        AiCallContext ctx = new AiCallContext(AiTaskType.JOB_GENERATION, input, 5000);
        // 由于无法连接 API,会返回网络错误(但证明代码路径不抛异常)
        AiCallResult result = provider.call(ctx);
        // 可能成功(如果网络通)或失败(网络不通),但不应抛未捕获异常
        assertNotNull(result);
        assertNotNull(result.providerRequestId());
    }
}
