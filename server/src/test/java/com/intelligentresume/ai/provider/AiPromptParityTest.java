package com.intelligentresume.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览与发送的**逐字节一致性**（AC-R32-1）。
 *
 * <p>R32 的核心不是"有个预览页面"，而是"预览看到的就是实际发出去的"。
 * 只要这两条路径各拼一次 prompt，就一定会漂移 —— 所以 provider 的 {@code buildMessages}
 * 直接委托 {@link AiMessageAssembler}，本测试断言两条路径对同一输入产出完全相同的结果。
 */
class AiPromptParityTest {

    private static final String CONTENT = "我叫张三，邮箱 zhangsan@example.com，电话 13800138000，请优化这段经历。";

    private BailianAiProvider provider() {
        return new BailianAiProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "test-key",
                "qwen-plus",
                "",
                10, 60, 1800, 60, 600, "JOB_GENERATION",
                new ObjectMapper(), null, new FailureCategoryClassifier());
    }

    private Map<String, Object> input(boolean includeContact) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content", CONTENT);
        input.put(AiMessageAssembler.INCLUDE_CONTACT_KEY, includeContact);
        return input;
    }

    @Test
    void providerPathEqualsPreviewPath() {
        for (boolean includeContact : new boolean[]{false, true}) {
            Map<String, Object> input = input(includeContact);
            List<Map<String, String>> sent = provider()
                    .buildMessages(new AiCallContext(AiTaskType.INLINE_OPTIMIZE, input));
            List<Map<String, String>> previewed = AiMessageAssembler.assemble(AiTaskType.INLINE_OPTIMIZE, input);
            assertEquals(previewed, sent,
                    "预览与实际发送必须是同一份内容（includeContact=" + includeContact + "）");
        }
    }

    @Test
    void defaultExcludesContactAndKeepsEverythingElse() {
        Map<String, Object> input = input(false);
        String sent = String.join("\n", AiMessageAssembler.assemble(AiTaskType.INLINE_OPTIMIZE, input)
                .stream().map(message -> message.get("content")).toList());

        assertFalse(sent.contains("zhangsan@example.com"), "默认必须不发送邮箱");
        assertFalse(sent.contains("13800138000"), "默认必须不发送手机号");
        assertTrue(sent.contains("[EMAIL]") && sent.contains("[PHONE]"), "应替换成占位符而不是直接删除");
        assertTrue(sent.contains("优化这段经历"), "正文不能被误删");
    }

    @Test
    void optInSendsContactAsIs() {
        Map<String, Object> input = input(true);
        String sent = String.join("\n", AiMessageAssembler.assemble(AiTaskType.INLINE_OPTIMIZE, input)
                .stream().map(message -> message.get("content")).toList());

        assertTrue(sent.contains("zhangsan@example.com"), "用户显式勾选后应原样发送");
        assertFalse(sent.contains("[EMAIL]"));
    }

    @Test
    void previewReportsWhatWouldBeRedacted() {
        AiMessageAssembler.PreviewPayload payload = AiMessageAssembler.preview(AiTaskType.INLINE_OPTIMIZE, input(false));
        assertFalse(payload.includeContact());
        assertTrue(payload.redactions().stream().anyMatch(item -> item.category().equals("EMAIL") && item.count() == 1));
        assertTrue(payload.redactions().stream().anyMatch(item -> item.category().equals("PHONE") && item.count() == 1));
    }
}
