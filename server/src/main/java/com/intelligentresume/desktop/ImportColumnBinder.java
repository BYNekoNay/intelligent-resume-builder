package com.intelligentresume.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入写入时的**列元数据与 JSON 绑定**（从 {@code DesktopImportService} 拆出，只为不让那个类继续变胖）。
 *
 * <p>两件事：
 * <ol>
 *   <li>用数据库元数据（{@code information_schema.columns}）回答「这张表有哪些列、哪些列是 JSON」，
 *       不硬编码列清单，schema 漂移时不会静默写错列；</li>
 *   <li>决定 JSON 列的绑定占位符 —— 这是本类存在的真正原因，见 {@link #placeholder}。</li>
 * </ol>
 *
 * <p>元数据的坑（已在导入写入那一批踩过并在此固化）：H2 的 {@code information_schema} 把
 * 表名/列名/类型名全部回成**大写**，而包里字段转下划线后是小写 —— 不统一转小写就一列都对不上。
 */
final class ImportColumnBinder {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 表 → 全部列（小写）。 */
    private final Map<String, Set<String>> columnCache = new LinkedHashMap<>();

    /** 表 → JSON 类型列（小写）。 */
    private final Map<String, Set<String>> jsonColumnCache = new LinkedHashMap<>();

    ImportColumnBinder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 待绑定值归一化：JSON 列的 {@code Map}/{@code List} 先转成 JSON **文本**，
     * 再交给 {@link #placeholder} 绑成 {@code ? FORMAT JSON}。
     *
     * <p>为什么必须做（2026-10-23 实测）：真实导出包里的 JSON 列是**嵌套对象**（解析后是 {@code Map}），
     * 而 {@code ps.setObject(i, LinkedHashMap)} 在 H2 上会被当成 {@code JAVA_OBJECT} 走 Java 序列化，
     * 抛出 {@code Data conversion error converting "CAST(X'aced0005...' AS JAVA_OBJECT)"} ——
     * 即真实包一条都导不进去。既有测试全是手写字符串包，所以这条路径从未被覆盖。
     *
     * <p>字符串值原样保留：那是手写包 / 旧格式的形态，{@code FORMAT JSON} 会把它当 JSON 文本解析。
     */
    Object valueFor(String table, String column, Object value) {
        if (value == null || !isJsonColumn(table, column) || value instanceof String) {
            return value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记录字段 " + table + "." + column + " 无法序列化成 JSON 文本", exception);
        }
    }

    Set<String> columnsOf(String table) {
        return columnCache.computeIfAbsent(table, key -> {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE lower(table_name) = ?",
                    String.class, key.toLowerCase());
            Set<String> columns = new LinkedHashSet<>();
            names.forEach(name -> columns.add(name.toLowerCase()));
            return columns;
        });
    }

    boolean owns(String table, String column) {
        return columnsOf(table).contains(column);
    }

    /** 该表该列是否是 JSON 列（写入侧要把 {@code Map} 先转成 JSON 文本，见 {@code DesktopImportService#prepareValues}）。 */
    boolean isJsonColumn(String table, String column) {
        return jsonColumnsOf(table).contains(column);
    }

    /**
     * JSON 列的写入占位符。
     *
     * <p><b>为什么不是普通的 {@code ?}</b>（实测字节形态，不是推测）：
     * <ul>
     *   <li>{@code ?}（字符串参数 / {@code setString}）→ H2 把内容存成 JSON <b>字符串标量</b>
     *       （双编码 {@code byte[35]="{\"a\":1}"}），之后 Hibernate 按 {@code Map} 读会抛
     *       {@code JsonJavaType} —— 该行等于写坏；</li>
     *   <li>{@code CAST(? AS JSON)} → <b>同样</b>是字符串标量（本机实测），不要用；</li>
     *   <li>{@code ? FORMAT JSON} → JSON <b>对象</b>（{@code byte[29]={"a":1}}），与 Hibernate
     *       自己写入的形态逐字节一致。</li>
     * </ul>
     * 只对「JSON 列 + 字符串值」生效。包里是 {@code Map} 的情况由
     * {@code DesktopImportService#jsonTextOf} 先转成 JSON 文本，再走到这里 ——
     * 直接把 {@code Map} 交给 {@code ?} 会被 H2 当成 {@code JAVA_OBJECT} 走 Java 序列化并报
     * {@code Data conversion error}（2026-10-23 实测）。
     * 本服务只在 desktop profile 激活、只跑 H2，用 H2 的 {@code FORMAT JSON} 不构成跨库分叉。
     */
    String placeholder(String table, String column, Object value) {
        return value instanceof String && jsonColumnsOf(table).contains(column) ? "? FORMAT JSON" : "?";
    }

    private Set<String> jsonColumnsOf(String table) {
        return jsonColumnCache.computeIfAbsent(table, key -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type FROM information_schema.columns WHERE lower(table_name) = ?",
                    key.toLowerCase());
            Set<String> columns = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                if ("json".equalsIgnoreCase(String.valueOf(row.get("data_type")))) {
                    columns.add(String.valueOf(row.get("column_name")).toLowerCase());
                }
            }
            return columns;
        });
    }
}
