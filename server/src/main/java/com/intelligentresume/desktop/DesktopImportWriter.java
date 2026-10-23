package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopLocalUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link DesktopImportPlanner.Plan} 的顺序执行写入。
 *
 * <h2>三条硬规则</h2>
 * <ol>
 *   <li><b>父记录先于其子记录</b>（计划保证），且父的本地主键**三种决策都要播种进 {@code idMap}** ——
 *       包括 SKIP 与 KEEP_LOCAL。旧实现只在 ADD 播种，导致父跳过后子记录取不到父键、
 *       外键保留 Web 主键，要么 FK 失败要么挂到错误的父记录上。</li>
 *   <li><b>子记录按自己的决策走</b>，不继承父的决策：父 SKIP 而子 ADD 时子照写（这正是批次 1.4 修的丢数据点）。</li>
 *   <li><b>当前版本指针最后统一回填</b>：{@code resume.current_version_id} 有外键指向版本行，
 *       逐条插一条回填一次会退化成「指向最后插入的那条」，与包里的 {@code currentVersionId} 无关。</li>
 * </ol>
 */
final class DesktopImportWriter {

    private static final Logger log = LoggerFactory.getLogger(DesktopImportWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final ImportColumnBinder columnBinder;
    private final ImportValueMapper valueMapper;
    private final DesktopLocalUserService localUserService;

    DesktopImportWriter(JdbcTemplate jdbcTemplate, ImportColumnBinder columnBinder,
                        ImportValueMapper valueMapper, DesktopLocalUserService localUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.columnBinder = columnBinder;
        this.valueMapper = valueMapper;
        this.localUserService = localUserService;
    }

    DesktopImportService.ImportResult apply(DesktopImportPlanner.Plan plan, Map<String, Object> resolutions) {
        Map<String, Long> idMap = new LinkedHashMap<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int keptLocal = 0;
        List<String> insertedIds = new ArrayList<>();

        for (DesktopImportPlanner.Entry entry : plan.entries()) {
            switch (entry.decision()) {
                case ADD -> {
                    Long localId = insert(entry, idMap);
                    seed(entry, localId, idMap);
                    upsertMapping(entry.table(), localId, entry.stableId());
                    inserted++;
                    insertedIds.add(entry.stableId());
                }
                case SKIP -> {
                    seed(entry, entry.localId(), idMap);
                    skipped++;
                }
                case CONFLICT -> {
                    seed(entry, entry.localId(), idMap);
                    Object resolution = resolutions.get(entry.stableId());
                    if (resolution == null || "KEEP_LOCAL".equals(resolution)) {
                        keptLocal++;
                    } else if ("USE_IMPORTED".equals(resolution)) {
                        update(entry, entry.record(), idMap);
                        updated++;
                    } else if (resolution instanceof Map<?, ?> merged) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mergedFields = (Map<String, Object>) merged;
                        update(entry, mergedFields, idMap);
                        updated++;
                    } else {
                        keptLocal++;
                    }
                }
                default -> throw new IllegalStateException("未知决策 " + entry.decision());
            }
        }
        backfillCurrentVersions(plan, idMap);
        log.info("桌面工作区导入完成：新增 {}、更新 {}、跳过 {}、保留本地 {}", inserted, updated, skipped, keptLocal);
        return new DesktopImportService.ImportResult(inserted, updated, skipped, keptLocal, List.copyOf(insertedIds));
    }

    // ------------------------------------------------------------------ 写入

    private Long insert(DesktopImportPlanner.Entry entry, Map<String, Long> idMap) {
        Map<String, Object> values = valueMapper.valuesFor(entry, entry.record(), idMap);
        String table = entry.table();
        List<String> columns = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (!columnBinder.owns(table, value.getKey())) {
                continue;   // 包里可能有本地表没有的字段（嵌套结构等），跳过
            }
            columns.add(value.getKey());
            args.add(value.getValue());
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("记录没有可导入的列（表 " + table + "）");
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append(columnBinder.placeholder(table, columns.get(i), args.get(i)));
        }
        String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        }, keyHolder);
        // H2 的 RETURN_GENERATED_KEYS 会把**整行**返回（多列），getKey() 会抛
        // 「should only be used when a single key is returned」—— 按列名取主键。
        return keyOf(keyHolder, table);
    }

    private void update(DesktopImportPlanner.Entry entry, Map<String, Object> source, Map<String, Long> idMap) {
        Map<String, Object> values = valueMapper.valuesFor(entry, source, idMap);
        String table = entry.table();
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (!columnBinder.owns(table, value.getKey())) {
                continue;
            }
            assignments.add(value.getKey() + " = " + columnBinder.placeholder(table, value.getKey(), value.getValue()));
            args.add(value.getValue());
        }
        if (assignments.isEmpty()) {
            return;
        }
        args.add(entry.localId());
        jdbcTemplate.update("UPDATE " + table + " SET " + String.join(",", assignments) + " WHERE id = ?",
                args.toArray());
    }

    private Long keyOf(KeyHolder keyHolder, String table) {
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null) {
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey())) {
                    return ((Number) entry.getValue()).longValue();
                }
            }
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 " + table + " 后拿不到主键");
        }
        return key.longValue();
    }

    /** 把父/子记录的本地主键播种进 idMap —— 三种决策都播，子记录才有父可指。 */
    private void seed(DesktopImportPlanner.Entry entry, Long localId, Map<String, Long> idMap) {
        if (localId != null) {
            idMap.put(entry.idMapKey(), localId);
        }
    }

    /** 映射用「先 UPDATE，0 行再 INSERT」：H2 不支持 {@code ON DUPLICATE KEY}，两边都要能跑。 */
    private void upsertMapping(String entityType, Long localId, String stableId) {
        int updated = jdbcTemplate.update(
                "UPDATE desktop_import_map SET local_id = ?, entity_type = ? WHERE source_stable_id = ?",
                localId, entityType, stableId);
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO desktop_import_map (user_id, entity_type, local_id, source_stable_id, imported_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                localUserService.localUserId(), entityType, localId, stableId);
    }

    /**
     * 把 {@code resume.current_version_id} 指向包里声明的当前版本。
     *
     * <p>解析不到（版本被撞号跳过 / 不在包内）→ 不动，绝不猜。
     */
    private void backfillCurrentVersions(DesktopImportPlanner.Plan plan, Map<String, Long> idMap) {
        for (DesktopImportPlanner.Entry entry : plan.entries()) {
            if (entry.child() != null || !"resumes".equals(entry.collection().name())) {
                continue;
            }
            Object webCurrent = entry.record().get("currentVersionId");
            if (webCurrent == null) {
                continue;
            }
            Long localResume = idMap.get(entry.idMapKey());
            Long localVersion = idMap.get("versions:" + webCurrent);
            if (localResume == null || localVersion == null) {
                log.warn("导入：简历 {} 的当前版本（{}）无法解析成本地版本，指针保持不变", entry.stableId(), webCurrent);
                continue;
            }
            jdbcTemplate.update("UPDATE resume SET current_version_id = ? WHERE id = ?", localVersion, localResume);
        }
    }
}
