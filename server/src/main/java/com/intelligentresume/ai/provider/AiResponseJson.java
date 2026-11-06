package com.intelligentresume.ai.provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型输出里抽出 JSON 载荷 —— 模型偶尔会在 JSON 外面裹一层说明文字，这里负责把 JSON 剥出来。
 *
 * <p>【链路位置】{@code BailianAiProvider#parseResponseContent} 先调 {@link #extract}
 * 剥壳，再交给 Jackson 解析；剥不出就返回原文由上层兜底。</p>
 *
 * <p>【改坏它会怎样】提取顺序或正则改动会让原本能解析的输出解析失败，
 * 用户看到的结果从"结构化 JSON"退化成 rawContent 原文。</p>
 *
 * <p>抽成独立类的原因有两个：</p>
 * <ol>
 *   <li>{@code BailianAiProvider} 踩着行数棘轮（已登记 597 行，只许下调），
 *       这段纯字符串处理放在里面会让它无法再改；</li>
 *   <li>它是**纯函数**，单独拎出来才能直接单测各种畸形输出，不必构造整个 provider。</li>
 * </ol>
 *
 * <p>提取顺序（保持原实现不变，避免行为漂移）：markdown 代码块 → 整体就是 JSON →
 * 嵌入的 JSON 对象 → 嵌入的 JSON 数组 → 原文（交给上层 fallback）。
 *
 * <p>要点 / 易错点:
 * <ul>
 *   <li>空输入返回 {@code "{}"} 而不是 null,保证调用方的 Jackson 解析不会因 NPE 提前失败。</li>
 *   <li>{@code OBJECT}/{@code ARRAY} 两个正则是贪婪的:正文里出现多段 JSON 时会从第一个左花括号
 *       一直取到最后一个右花括号,把中间的非 JSON 文本一起带进来。改这里前先想清楚
 *       "多段 JSON" 的场景怎么处理,不要只是把量词改成懒惰。</li>
 *   <li>抽不出来时返回原文,是刻意的兜底:上层会把原文放进 {@code rawContent} 给用户看,
 *       而不是返回一片空白。</li>
 * </ul>
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
