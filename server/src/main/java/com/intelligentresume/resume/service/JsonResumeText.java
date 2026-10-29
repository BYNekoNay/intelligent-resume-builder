package com.intelligentresume.resume.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * JSON Resume 导出用的取值/格式化工具（包内可见，无状态）。
 *
 * <p>抽出来的理由：导出映射里「从 {@code Map<String,Object>} 里安全地取第一个非空文本」
 * 出现了十几次，散落各处会让每个章节的取值口径悄悄分叉（某处 trim、某处不 trim，
 * 某处把列表拼成逗号、某处取第一个）。统一到一处，才有资格谈"确定性导出"。
 */
final class JsonResumeText {

    /** 官方 {@code iso8601} 定义：{@code YYYY-MM-DD} / {@code YYYY-MM} / {@code YYYY}。 */
    static final Pattern ISO8601 =
            Pattern.compile("^([1-2][0-9]{3}-[0-1][0-9]-[0-3][0-9]|[1-2][0-9]{3}-[0-1][0-9]|[1-2][0-9]{3})$");

    /** 技术栈/关键词的自由文本分隔符（中英文混排的常见写法）。 */
    private static final Pattern KEYWORD_SEPARATORS = Pattern.compile("[,，、;；/|]+");

    private JsonResumeText() {
    }

    static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return map;
    }

    static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) return out;
        for (Object entry : list) if (entry instanceof Map<?, ?>) out.add(mapValue(entry));
        return out;
    }

    /** 按给定键顺序取第一个非空文本；列表值拼成逗号串。 */
    static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) continue;
            String text = value instanceof List<?> list
                    ? joinNonBlank(list.stream().map(String::valueOf).toList(), ", ")
                    : String.valueOf(value);
            if (text != null && !text.isBlank()) return text.trim();
        }
        return null;
    }

    /** 亮点既可能是字符串数组，也可能是 {@code [{text: ...}]} 结构。 */
    static List<String> textList(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?>) {
                String text = firstText(mapValue(entry), "text", "name", "description");
                if (text != null) out.add(text);
            } else if (entry != null) {
                String text = String.valueOf(entry).trim();
                if (!text.isEmpty()) out.add(text);
            }
        }
        return out;
    }

    static List<String> splitKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : KEYWORD_SEPARATORS.split(raw)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    static String joinNonBlank(List<String> parts, String separator) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) if (part != null && !part.isBlank()) kept.add(part.trim());
        return kept.isEmpty() ? null : String.join(separator, kept);
    }

    static void putText(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value.trim());
    }

    /**
     * 日期只在**符合官方 iso8601 模式**时才写入：官方 schema 对日期有 pattern 约束，
     * 写一个「2021年3月」进去会让整份文档校验失败。宁可省略，由 {@code period} 承载原始表述。
     */
    static void putDate(Map<String, Object> out, String key, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (ISO8601.matcher(trimmed).matches()) out.put(key, trimmed);
    }

    /** 把集合型章节的每个条目映射成标准形状，并丢掉映射后为空的条目。 */
    static List<Map<String, Object>> items(Object raw, Function<Map<String, Object>, Map<String, Object>> mapper) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : maps(raw)) {
            Map<String, Object> mapped = mapper.apply(item);
            if (!mapped.isEmpty()) out.add(mapped);
        }
        return out;
    }
}
