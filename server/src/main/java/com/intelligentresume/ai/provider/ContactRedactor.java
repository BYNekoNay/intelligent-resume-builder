package com.intelligentresume.ai.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联系方式脱敏（R32：默认排除联系方式）—— 发给模型前把邮箱/手机号替换成占位符。
 *
 * <p>【业务问题】用户的联系方式属于敏感个人信息，默认不应该随简历内容一起发往第三方模型服务。
 * 替换成可读的占位符（{@code [EMAIL]} / {@code [PHONE]}）而不是删掉：模型仍然知道
 * "这里原来有个联系方式"，生成时不会把它当成缺失内容去自行编造。</p>
 *
 * <p>【链路位置】只被 {@code AiMessageAssembler} 调用：实际发送前对消息做 {@link #redact}，
 * 预览用 {@link #summarize} 统计"将被替换掉什么"给用户看。</p>
 *
 * <p>【改坏它会怎样】正则漏匹配 = 联系方式泄露给模型；正则过宽 = 把版本号、金额等正常数字
 * 误替换，简历内容被破坏。</p>
 *
 * <p>只脱两类：**邮箱**与**手机号**。刻意不脱 URL、不脱普通文本 ——
 * 简历里的项目链接、GitHub 地址是生成质量的依据，删掉会让结果明显变差；
 * 而 R32 要防的是"联系方式被发出去"，那两类就够。</p>
 *
 * <p>顺序有意义：先替换邮箱再替换手机号，避免邮箱里的数字片段被当手机号切碎。</p>
 *
 * <p>要点 / 易错点:</p>
 * <ul>
 *   <li>只处理消息的 {@code content} 字段,其余字段(role 等)原样复制,不做通用递归。</li>
 *   <li>手机号正则带前后边界断言,防止把版本号、金额等长数字误伤;改正则时要同时看
 *       {@link #summarize} 的统计口径,否则"预览说替换了 N 处、实际替换 M 处"。</li>
 *   <li>{@link #summarize} 必须在**未脱敏**文本上统计,且统计手机号前要先摘掉邮箱,
 *       否则邮箱里的数字段会被重复计数。</li>
 * </ul>
 */
public final class ContactRedactor {

    /** 邮箱：够宽但不贪心，避免把句子吞掉。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 手机号/固话：11 位大陆手机（可带 +86 / 空格 / 连字符），或带区号的固话。
     * 用边界断言避免把「版本号 1.2.3」「金额 138001380001」这类长数字误伤。
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\dA-Za-z])(?:\\+?86[\\s-]?)?1[3-9]\\d{9}(?![\\dA-Za-z])"
                    + "|(?<![\\dA-Za-z])0\\d{2,3}[\\s-]?\\d{7,8}(?![\\dA-Za-z])");

    public static final String EMAIL_PLACEHOLDER = "[EMAIL]";
    public static final String PHONE_PLACEHOLDER = "[PHONE]";

    private ContactRedactor() {
    }

    /** 一类被替换的次数。 */
    public record Redaction(String category, int count) {
    }

    public static List<Map<String, String>> redact(List<Map<String, String>> messages) {
        return messages.stream().map(ContactRedactor::redactMessage).toList();
    }

    private static Map<String, String> redactMessage(Map<String, String> message) {
        Map<String, String> copy = new LinkedHashMap<>(message);
        copy.computeIfPresent("content", (key, text) -> redactText(text));
        return copy;
    }

    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutEmail = EMAIL.matcher(text).replaceAll(EMAIL_PLACEHOLDER);
        return PHONE.matcher(withoutEmail).replaceAll(PHONE_PLACEHOLDER);
    }

    /** 统计「如果脱敏会替换掉什么」，用于预览里的摘要。计数针对原始文本。 */
    public static List<Redaction> summarize(List<Map<String, String>> messages) {
        int emails = 0;
        int phones = 0;
        for (Map<String, String> message : messages) {
            String text = message.get("content");
            if (text == null || text.isEmpty()) {
                continue;
            }
            emails += count(EMAIL, text);
            // 手机号在原文本里统计时要把邮箱先摘掉，否则邮箱里的数字段会被重复计数
            phones += count(PHONE, EMAIL.matcher(text).replaceAll(" "));
        }
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        if (emails > 0) {
            byCategory.put("EMAIL", emails);
        }
        if (phones > 0) {
            byCategory.put("PHONE", phones);
        }
        return byCategory.entrySet().stream()
                .map(entry -> new Redaction(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
