package com.intelligentresume.desktop;

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

    /** 表 → 全部列（小写）。 */
    private final Map<String, Set<String>> columnCache = new LinkedHashMap<>();

    /** 表 → JSON 类型列（小写）。 */
    private final Map<String, Set<String>> jsonColumnCache = new LinkedHashMap<>();

    ImportColumnBinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
     * 只对「JSON 列 + 字符串值」生效；包里已经是 Map 的值仍走普通 {@code ?}（无法作为 JSON 文本绑定）。
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
