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

/** Builds the model-visible representation of a career material. */
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
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i)(?:e[-_ ]?mail|phone|mobile|telephone|tel|address|postal[-_ ]?code|contact|" +
                    "website|url|portfolio|linkedin|github|wechat|微信|邮箱|电话|手机|地址|联系方式)");

    @SuppressWarnings("unchecked")
    public CareerMaterial sanitize(CareerMaterial material) {
        Map<String, Object> content = material.getContentJson() == null
                ? new LinkedHashMap<>() : sanitizeMap(material.getContentJson());
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
