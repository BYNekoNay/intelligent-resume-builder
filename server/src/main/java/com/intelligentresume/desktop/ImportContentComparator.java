package com.intelligentresume.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 判断「本地这一行」与「包里这一条」是不是同一份内容（R37）。
 *
 * <h2>三条容易踩空的规则</h2>
 * <ol>
 *   <li><b>只比较包里给出的字段</b>：本地表还有 id / user_id / 解析结果等包外字段，
 *       全量比较会让「内容其实一样」永远判成冲突；</li>
 *   <li><b>字段名要归一</b>：导出是驼峰（{@code companyName}），本地列是下划线（{@code company_name}）；</li>
 *   <li><b>值要按列类型归一</b>（批次 1.4 新增，此前完全没有）：
 *       JSON 列本地读回是 {@code byte[]}、包里是 {@code Map}；DATETIME 本地读回是 {@code Timestamp}、
 *       包里是 ISO 字符串；数字本地是 {@code Long}、包里是 {@code Integer}。
 *       不归一的直接后果是**真实包第二次导入满屏冲突**（旧用例全是手写同类型值，所以从未暴露）。</li>
 * </ol>
 *
 * <p>归一只对「JSON 列 / 时间 / 数字」三类生效，其余仍是原值比较 —— 避免过度放宽后
 * 「真实变更被判成没有变化」，那是比误报冲突更危险的方向（该更新的没更新）。
 */
final class ImportContentComparator {

    /** 归一化比较时要忽略的易变字段（时间戳变了不算内容变化）。 */
    static final Set<String> VOLATILE_FIELDS = Set.of(
            "id", "stableId", "userId", "createdAt", "updatedAt", "deletedAt", "version");

    /**
     * 引用类字段：包里是 Web 主键、本地是本地主键，值本来就不相等，必须跳过。
     *
     * <p>{@code applicationRecordId} 与 {@code restoredFromVersionId} 是批次 1.4 补的 ——
     * 嵌套记录进入比较之后，前者会让**每条状态历史**都判冲突，后者会让带还原来源的版本都判冲突。
     */
    static final Set<String> REFERENCE_FIELDS = Set.of(
            "currentVersionId", "jobDescriptionId", "resumeId", "resumeVersionId", "createdBy",
            "applicationRecordId", "restoredFromVersionId", "parentTaskId", "resultResumeVersionId");

    private static final DateTimeFormatter FIXED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final ImportColumnBinder columnBinder;
    private final ObjectMapper objectMapper;

    /** 嵌套集合字段**从注册表派生**，不另写清单（否则注册表加了子集合、这里忘加，就是 careerMaterials 同型缺陷）。 */
    private final Set<String> nestedFields;

    /** 表 → 归一化后的 JSON 列名。 */
    private final Map<String, Set<String>> jsonColumns = new LinkedHashMap<>();

    ImportContentComparator(ImportColumnBinder columnBinder, ObjectMapper objectMapper) {
        this.columnBinder = columnBinder;
        this.objectMapper = objectMapper;
        Set<String> children = new LinkedHashSet<>();
        ImportCollectionRegistry.collections()
                .forEach(collection -> collection.children().forEach(child -> children.add(child.name())));
        this.nestedFields = children;
    }

    boolean same(String table, Map<String, Object> local, Map<String, Object> imported) {
        Map<String, Object> localView = new LinkedHashMap<>();
        local.forEach((key, value) -> localView.put(normalizeKey(key), value));

        Map<String, Object> left = new LinkedHashMap<>();
        Map<String, Object> right = new LinkedHashMap<>();
        imported.forEach((key, value) -> {
            if (VOLATILE_FIELDS.contains(key) || "stableId".equals(key)
                    || nestedFields.contains(key) || REFERENCE_FIELDS.contains(key)) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            // 本地表**没有这一列** → 不可比（不是「值不同」）。例如投递的 companyName 在本地表里没有对应列。
            if (!localView.containsKey(normalizedKey)) {
                return;
            }
            right.put(normalizedKey, comparable(table, normalizedKey, value));
            left.put(normalizedKey, comparable(table, normalizedKey, localView.get(normalizedKey)));
        });
        return objectMapper.valueToTree(left).toString().equals(objectMapper.valueToTree(right).toString());
    }

    /** 字段名归一：驼峰与下划线视作同一个字段。 */
    String normalizeKey(String key) {
        return key.replace("_", "").toLowerCase();
    }

    // ------------------------------------------------------------------ 值归一

    private Object comparable(String table, String normalizedKey, Object value) {
        if (value == null) {
            return null;
        }
        if (isJsonColumn(table, normalizedKey)) {
            return jsonOf(value);
        }
        if (value instanceof Number number) {
            // 防 IntNode vs LongNode：H2 的 BIGINT 读回 Long，包内可能是 Integer
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        LocalDateTime temporal = temporalOf(value);
        if (temporal != null) {
            return FIXED.format(temporal);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return stable(value);
        }
        return value;
    }

    private boolean isJsonColumn(String table, String normalizedKey) {
        return jsonColumns
                .computeIfAbsent(table, key -> {
                    Set<String> normalized = new LinkedHashSet<>();
                    columnBinder.columnsOf(key).stream()
                            .filter(column -> columnBinder.isJsonColumn(key, column))
                            .map(this::normalizeKey)
                            .forEach(normalized::add);
                    return normalized;
                })
                .contains(normalizedKey);
    }

    /** JSON 列统一成 {@code JsonNode} 比较（对象字段顺序不敏感、数组顺序敏感，正是要的语义）。 */
    private Object jsonOf(Object value) {
        if (value instanceof byte[] bytes) {
            return readTreeOr(new String(bytes, StandardCharsets.UTF_8), value);
        }
        if (value instanceof String text) {
            return readTreeOr(text, value);
        }
        return objectMapper.valueToTree(value);
    }

    private Object readTreeOr(String text, Object fallback) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node == null ? fallback : node;
        } catch (Exception exception) {
            // 不是合法 JSON（历史坏行）→ 退回原值比较，不要在比较阶段抛错
            return fallback;
        }
    }

    /** 时间列统一成固定格式：本地是 Timestamp、包里是 ISO 字符串，直接比会永远不等。 */
    private LocalDateTime temporalOf(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        if (value instanceof String text && looksTemporal(text)) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception exception) {
                return null;
            }
        }
        return null;
    }

    private boolean looksTemporal(String text) {
        return text.length() >= 16 && text.charAt(4) == '-' && text.charAt(7) == '-'
                && (text.charAt(10) == 'T' || text.charAt(10) == ' ');
    }

    @SuppressWarnings("unchecked")
    private Object stable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new TreeMap<>();
            map.forEach((key, nestedValue) -> nested.put(String.valueOf(key), stable(nestedValue)));
            return nested;
        }
        List<Object> list = new ArrayList<>();
        ((List<?>) value).forEach(element -> list.add(stable(element)));
        return list;
    }
}
