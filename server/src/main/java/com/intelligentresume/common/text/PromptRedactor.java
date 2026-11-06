package com.intelligentresume.common.text;

import java.util.regex.Pattern;

/**
 * 提示词「值级打码」的单一出处：把文本里内嵌的邮箱、手机号、URL、身份证号替换成
 * {@code [EMAIL]} / {@code [PHONE]} / {@code [URL]} / {@code [ID]} 占位符。
 *
 * <p>【业务问题】用户的联系方式/证件号属于敏感个人信息，默认不随内容发往第三方模型服务；
 * 替换成占位符而不是删除，模型仍知道"这里原来有个联系方式"，不会当成缺失内容去编造。</p>
 *
 * <p>【链路位置】被三个脱敏方共用：素材快照 {@code CareerMaterialAiSnapshotSanitizer}、
 * 沟通文案 {@code CommunicationAiPromptBuilder}、ATS 分析 {@code AtsAiPromptBuilder}。
 * 各处的「键名清理」（按字段名整字段丢弃）策略不同、各自实现；本类只收敛值级正则与替换顺序。</p>
 *
 * <p>【改坏它会怎样】正则漏匹配 = 联系方式泄露给模型；改动替换顺序会改变输出
 * （先邮箱后手机：避免邮箱里的数字片段被当手机号切碎），已有测试锚定输出结果。</p>
 *
 * <p>注意：手机号正则只认中国大陆手机号（素材侧的「国际号码」与「地址标签」规则
 * 属于素材侧场景特有，未收敛进来，由调用方在 {@link #mask} 前后自行追加）。</p>
 */
public final class PromptRedactor {

    public static final String EMAIL_PLACEHOLDER = "[EMAIL]";
    public static final String PHONE_PLACEHOLDER = "[PHONE]";
    public static final String URL_PLACEHOLDER = "[URL]";
    public static final String ID_PLACEHOLDER = "[ID]";

    static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    static final Pattern CHINESE_PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s,;)]+", Pattern.CASE_INSENSITIVE);
    static final Pattern ID_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private PromptRedactor() {
    }

    /**
     * 值级打码，替换顺序固定：EMAIL → PHONE → URL → ID。
     * 顺序是行为的一部分（改动会改变输出），不要调整。
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL_PLACEHOLDER);
        result = CHINESE_PHONE_PATTERN.matcher(result).replaceAll(PHONE_PLACEHOLDER);
        result = URL_PATTERN.matcher(result).replaceAll(URL_PLACEHOLDER);
        return ID_PATTERN.matcher(result).replaceAll(ID_PLACEHOLDER);
    }
}
