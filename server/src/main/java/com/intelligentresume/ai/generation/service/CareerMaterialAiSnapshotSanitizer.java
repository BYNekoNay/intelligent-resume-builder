package com.intelligentresume.ai.generation.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 生成「可以交给模型看」的素材副本 —— 隐私在这里被剥掉，原始对象不动。
 *
 * <h2>两道不同的闸，别混为一谈</h2>
 * <ol>
 *   <li><b>丢键</b>（{@link #SENSITIVE_KEY_PATTERN}）：键名命中 email/phone/address/contact/url/
 *       linkedin/微信… 时，**整个字段连同值一起丢掉**；</li>
 *   <li><b>打码</b>（{@link #sanitizeText}）：文本里内嵌的邮箱、手机、国际号码、URL、身份证号、
 *       「地址：xxx」片段，替换成 {@code [EMAIL]} / {@code [PHONE]} / {@code [URL]} / {@code [ID]} /
 *       {@code [ADDRESS]}。</li>
 * </ol>
 * 只做一件都不够：只丢键会漏掉正文里的联系方式；只打码会漏掉结构化字段（而且数字类型的值打不了码）。
 *
 * <h2>业绩类（ACHIEVEMENT）额外一条</h2>
 * 用户没选「精确展示」时删掉 {@code metricExactValue}，避免为了润色把精确数字带出去；
 * 选了精确展示才把它同步到 {@code metricDisplayValue}。同时**重新拼** sourceText 而不是沿用原文 ——
 * 原文里很可能就写着那个精确值。
 *
 * <h2>边界</h2>
 * 本类只脱敏，不做长度控制（字节预算见 {@code MaterialPromptTextBudget}）。
 * 返回的是**新对象**，调用方不得把它当实体回写库。
 */
@Component
public class CareerMaterialAiSnapshotSanitizer {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CHINESE_PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern INTERNATIONAL_PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[ -]?)?\\(?\\d{2,4}\\)?(?:[ -]\\d{3,4}){2,3}(?!\\d)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s,;)]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern LABELED_ADDRESS_PATTERN = Pattern.compile(
            "(?i)(?:home\\s+address|mailing\\s+address|address|地址)\\s*[:：]\\s*[^\\n;]+\\s*");
    /**
     * 键名级黑名单：命中即**整个字段连同值一起丢弃**（与 {@link #sanitizeText} 的文本打码是两道闸）。
     *
     * <p>用正则而不是精确匹配，是为了覆盖 {@code phoneNumber} / {@code emailAddress} 这类派生键名；
     * 代价是会误伤含 contact/url 的普通字段，属于安全侧的刻意取舍。
     */
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i)(?:e[-_ ]?mail|phone|mobile|telephone|tel|address|postal[-_ ]?code|contact|" +
                    "website|url|portfolio|linkedin|github|wechat|微信|邮箱|电话|手机|地址|联系方式)");

    @SuppressWarnings("unchecked")
    public CareerMaterial sanitize(CareerMaterial material) {
        Map<String, Object> content = material.getContentJson() == null
                ? new LinkedHashMap<>() : sanitizeMap(material.getContentJson());
        // 业绩类：没选「精确展示」就不让精确数字离开服务端；选了才同步到展示字段。
        if ("ACHIEVEMENT".equals(material.getMaterialType().name())) {
            String mode = Objects.toString(content.get("metricDisplayMode"), "QUALITATIVE");
            if (!"EXACT".equals(mode)) {
                content.remove("metricExactValue");
            } else if (content.get("metricExactValue") != null) {
                content.put("metricDisplayValue", content.get("metricExactValue"));
            }
        }

        CareerMaterial safe = new CareerMaterial();
        safe.setId(material.getId());
        safe.setUserId(material.getUserId());
        safe.setMaterialType(material.getMaterialType());
        safe.setTitle(sanitizeText(material.getTitle()));
        safe.setUsagePreference(material.getUsagePreference());
        safe.setContentJson(content);
        safe.setSourceText("ACHIEVEMENT".equals(material.getMaterialType().name())
                ? renderAchievementSourceText(safe.getTitle(), content)
                : sanitizeText(material.getSourceText()));
        return safe;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !SENSITIVE_KEY_PATTERN.matcher(key).find()) {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String textKey = key == null ? "" : key.toString();
                if (!SENSITIVE_KEY_PATTERN.matcher(textKey).find()) {
                    nested.put(textKey, sanitizeValue(nestedValue));
                }
            });
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::sanitizeValue).toList();
        }
        return value instanceof String text ? sanitizeText(text) : value;
    }

    private String sanitizeText(String text) {
        if (text == null || text.isBlank()) return text;
        String result = text.replaceAll("\\r\\n?", "\\n");
        result = LABELED_ADDRESS_PATTERN.matcher(result).replaceAll("[ADDRESS]");
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL]");
        result = CHINESE_PHONE_PATTERN.matcher(result).replaceAll("[PHONE]");
        result = INTERNATIONAL_PHONE_PATTERN.matcher(result).replaceAll("[PHONE]");
        result = URL_PATTERN.matcher(result).replaceAll("[URL]");
        result = ID_PATTERN.matcher(result).replaceAll("[ID]");
        return result;
    }

    private String renderAchievementSourceText(String title, Map<String, Object> content) {
        List<String> parts = new ArrayList<>();
        for (Object value : new Object[]{title, content.get("scenario"), content.get("action"),
                content.get("outcome"), content.get("metricName"),
                content.get("metricDisplayValue"), content.get("period")}) {
            String text = nonBlank(value);
            if (!text.isBlank()) parts.add(text);
        }
        return String.join("; ", parts);
    }

    private String nonBlank(Object value) {
        String text = value == null ? "" : value.toString().trim();
        return text;
    }
}
