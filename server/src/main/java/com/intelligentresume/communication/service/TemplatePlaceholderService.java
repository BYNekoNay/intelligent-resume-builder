package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符服务：白名单校验 + 纯字符串替换填充。
 *
 * <p>占位符白名单：{{candidateName}} {{jobTitle}} {{companyName}} {{topSkill}}
 * {{location}} {{email}} {{phone}}。联系方式（email/phone）由服务端从简历 basics 合并，
 * 模板填充为纯字符串替换，不经过任何模型。
 */
@Component
public class TemplatePlaceholderService {

    public static final Set<String> PLACEHOLDER_WHITELIST = Set.of(
            "candidateName", "jobTitle", "companyName", "topSkill", "location", "email", "phone");

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /**
     * 校验 bodyText 中的占位符均来自白名单，非法占位符抛 40001。
     */
    public void validate(String bodyText) {
        List<String> illegal = illegalPlaceholders(bodyText);
        if (!illegal.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "模板包含非法占位符: " + String.join(", ", illegal));
        }
    }

    /**
     * 返回 bodyText 中所有不在白名单内的占位符名。
     */
    public List<String> illegalPlaceholders(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return List.of();
        LinkedHashSet<String> illegal = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(bodyText);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!PLACEHOLDER_WHITELIST.contains(name)) {
                illegal.add(name);
            }
        }
        return new ArrayList<>(illegal);
    }

    /**
     * 从 bodyText 提取全部占位符名（去重，保持出现顺序）。
     */
    public List<String> extractPlaceholders(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(bodyText);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * 用真实简历 JSON + JD 填充白名单占位符。
     *
     * <p>缺失值占位符原样保留，并列入返回的 missingPlaceholders。
     */
    public FillResult fill(String bodyText, Map<String, Object> resumeJson, JobDescription job) {
        Map<String, String> values = buildValues(resumeJson, job);
        List<String> missing = new ArrayList<>();
        for (String placeholder : extractPlaceholders(bodyText)) {
            String value = values.get(placeholder);
            if (value == null || value.isBlank()) {
                if (!missing.contains(placeholder)) missing.add(placeholder);
            }
        }
        String filled = bodyText;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            filled = filled.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return new FillResult(filled, missing);
    }

    private Map<String, String> buildValues(Map<String, Object> resumeJson, JobDescription job) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Object> basics = asMap(resumeJson == null ? null : resumeJson.get("basics"));
        values.put("candidateName", asString(basics.get("name")));
        values.put("location", asString(basics.get("location")));
        values.put("email", asString(basics.get("email")));
        values.put("phone", asString(basics.get("phone")));
        values.put("jobTitle", job == null ? "" : asString(job.getTitle()));
        values.put("companyName", job == null ? "" : asString(job.getCompanyName()));
        values.put("topSkill", firstSkill(resumeJson == null ? null : resumeJson.get("skills")));
        return values;
    }

    private String firstSkill(Object value) {
        if (!(value instanceof List<?> skills) || skills.isEmpty()) return "";
        Object first = skills.get(0);
        if (first instanceof String text) return text;
        if (first instanceof Map<?, ?> item) {
            return asString(item.get("name"));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record FillResult(String filledBody, List<String> missingPlaceholders) {}
}
