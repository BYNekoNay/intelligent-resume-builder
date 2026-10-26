package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组装「要发给模型的确切消息」—— **预览与实际发送的唯一实现**（R32）。
 *
 * <p>为什么必须只有一份：R32 要求"展示将要发送的确切内容"。如果预览自己拼一遍、provider 再拼一遍，
 * 两份代码一定会漂移，而漂移的结果是**用户看到的和实际发出去的不一样** —— 这比没有预览更糟。
 * 所以 provider 的 {@code buildMessages} 直接委托本类，并由 {@code AiPromptParityTest}
 * 断言"预览路径 == 发送路径"。
 *
 * <p>脱敏也在这层完成（{@link ContactRedactor}）：预览与发送都经过它，所以"看到的"必然就是"发出去的"。
 * 是否包含联系方式由 input 里的内部键 {@code _includeContact} 决定（默认 false = 排除）。
 * 以 {@code _} 开头的键本来就不会进入 prompt 正文（{@code PromptTemplates} 会过滤），
 * 因此这个开关不会污染内容。
 */
public final class AiMessageAssembler {

    /** 内部键：是否把联系方式原样发给模型。默认 false（R32：默认排除）。 */
    public static final String INCLUDE_CONTACT_KEY = "_includeContact";

    private AiMessageAssembler() {
    }

    /** 实际发送用：按 input 里的开关决定是否脱敏。 */
    public static List<Map<String, String>> assemble(AiTaskType type, Map<String, Object> input) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        List<Map<String, String>> raw = rawMessages(type, safeInput);
        return includeContact(safeInput) ? raw : ContactRedactor.redact(raw);
    }

    /**
     * 预览用：返回「实际会发送的内容」+「本轮脱敏摘要」。
     *
     * <p>摘要来自**未脱敏**版本的统计 —— 否则用户永远看不到"刚才被替换掉了什么"。
     * 这两个列表来源同一次 {@link #rawMessages} 调用，因此预览与实际发送不会出现两个版本。
     */
    public static PreviewPayload preview(AiTaskType type, Map<String, Object> input) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        List<Map<String, String>> raw = rawMessages(type, safeInput);
        boolean withContact = includeContact(safeInput);
        List<Map<String, String>> sent = withContact ? raw : ContactRedactor.redact(raw);
        List<ContactRedactor.Redaction> redactions = withContact ? List.of() : ContactRedactor.summarize(raw);
        return new PreviewPayload(sent, withContact, redactions);
    }

    private static boolean includeContact(Map<String, Object> input) {
        return Boolean.TRUE.equals(input.get(INCLUDE_CONTACT_KEY));
    }

    private static List<Map<String, String>> rawMessages(AiTaskType type, Map<String, Object> input) {
        String systemPrompt = asText(input.get("_systemPrompt"));
        String taskPrompt = asText(input.get("_taskPrompt"));
        String dataPrompt = asText(input.get("_dataPrompt"));

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
            String userContent = (taskPrompt != null ? taskPrompt + "\n\n" : "")
                    + (dataPrompt != null ? dataPrompt : "");
            messages.add(Map.of("role", "user", "content", userContent));
        } else {
            messages.add(Map.of("role", "system", "content", PromptTemplates.systemFor(type, input)));
            messages.add(Map.of("role", "user", "content", PromptTemplates.userPromptFor(type, input)));
        }
        return messages;
    }

    private static String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    public record PreviewPayload(List<Map<String, String>> messages,
                                 boolean includeContact,
                                 List<ContactRedactor.Redaction> redactions) {
    }
}
