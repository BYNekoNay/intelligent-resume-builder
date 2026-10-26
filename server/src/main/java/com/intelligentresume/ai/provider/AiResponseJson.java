package com.intelligentresume.ai.provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型输出里抽出 JSON 载荷。
 *
 * <p>抽成独立类的原因有两个：
 * <ol>
 *   <li>{@code BailianAiProvider} 踩着行数棘轮（已登记 597 行，只许下调），
 *       这段纯字符串处理放在里面会让它无法再改；</li>
 *   <li>它是**纯函数**，单独拎出来才能直接单测各种畸形输出，不必构造整个 provider。</li>
 * </ol>
 *
 * <p>提取顺序（保持原实现不变，避免行为漂移）：markdown 代码块 → 整体就是 JSON →
 * 嵌入的 JSON 对象 → 嵌入的 JSON 数组 → 原文（交给上层 fallback）。
 */
final class AiResponseJson {

    private static final Pattern BLOCK = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern OBJECT = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern ARRAY = Pattern.compile("\\[[\\s\\S]*]");

    private AiResponseJson() {
    }

    static String extract(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String trimmed = content.trim();

        Matcher blockMatcher = BLOCK.matcher(trimmed);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        Matcher objMatcher = OBJECT.matcher(trimmed);
        if (objMatcher.find()) {
            return objMatcher.group();
        }

        Matcher arrMatcher = ARRAY.matcher(trimmed);
        if (arrMatcher.find()) {
            return arrMatcher.group();
        }

        // 抽不出来就返回原文：上层解析失败会走 fallback（把原文放进 rawContent），
        // 这样"模型没按格式回"时用户还能看到内容，而不是一片空白
        return trimmed;
    }
}
