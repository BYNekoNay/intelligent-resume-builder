package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组装「要发给模型的确切消息」—— **预览与实际发送的唯一实现**（R32）。
 *
 * <p>【业务问题】R32 要求"用户先看到将要发送什么、再确认发送"。这只有一条路成立：
 * 预览和真正发送必须用同一段代码拼消息。两份实现一定会漂移，漂移的结果是
 * **用户看到的和实际发出去的不一样** —— 这比没有预览更糟。</p>
 *
 * <p>【链路位置】provider 的 {@code buildMessages} 委托 {@link #assemble}（实际发送）；
 * {@code AiPreviewService} 调 {@link #preview}（预览展示）。脱敏（{@link ContactRedactor}）
 * 也在这一层完成，所以"看到的"必然就是"发出去的"。由 {@code AiPromptParityTest}
 * 断言"预览路径 == 发送路径"。</p>
 *
 * <p>【改坏它会怎样】在这里加"预览专用"分支或脱敏逻辑漏掉一类联系方式，
 * 都会直接破坏 R32 的核心承诺。</p>
 *
 * <p>脱敏是否包含联系方式由 input 里的内部键 {@code _includeContact} 决定（默认 false = 排除）。
 * 以 {@code _} 开头的键本来就不会进入 prompt 正文（{@code PromptTemplates} 会过滤），
 * 因此这个开关不会污染内容。</p>
 *
 * <p>要点 / 易错点:</p>
 * <ul>
 *   <li>两条构造路径:input 里给了 {@code _systemPrompt} 就直接用它(领域流程已拼好的三段式
 *       task/data 分段),否则回落到 {@link PromptTemplates}。判断依据是 {@code _systemPrompt}
 *       是否存在 —— 只给 {@code _taskPrompt} 不给 system 会被当成空。</li>
 *   <li>脱敏只覆盖四类中的两类(邮箱、手机号),见 {@link ContactRedactor} 的取舍。</li>
 *   <li>{@link #preview} 与 {@link #assemble} 必须共用 {@code rawMessages} 的结果;
 *       任何"为了预览顺手优化一下"的分支都会让展示内容与实际发送内容漂移。</li>
 * </ul>
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
