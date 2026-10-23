package com.intelligentresume.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把导入包一次性翻译成**执行计划**：扁平、父记录在前、子记录紧随其父，每条带自己的决策。
 *
 * <h2>为什么要有计划这一层</h2>
 * 旧实现里「分类」（{@code preview}）与「写入」（{@code apply}）是两套遍历：preview 只走 4 个顶层集合，
 * apply 只在**父记录判 ADD** 时调 {@code insertChildren} 写子记录。后果是：
 * <ul>
 *   <li>嵌套集合从不参与预览 —— 版本内容变了，既不判冲突也不会被更新，且毫无提示；</li>
 *   <li>父记录判 SKIP / CONFLICT 时，**子记录根本不写**（数据丢失，比"未预览"更严重）；</li>
 *   <li>父 SKIP 时 {@code idMap} 没播种，子记录外键只好保留 Web 主键 → FK 失败或挂错父。</li>
 * </ul>
 * 现在两者都只消费同一份计划，且子记录是**独立条目**（有自己的 stableId 与决策），
 * 所以"父的分支决定子是否写"这条隐式规则在结构上不存在了。
 */
final class DesktopImportPlanner {

    private static final Logger log = LoggerFactory.getLogger(DesktopImportPlanner.class);

    /** 「本地已存在同编号的简历版本」时给用户的固定说明（与内容冲突区分开，否则用户会以为要二选一）。 */
    static final String REASON_VERSION_NO_OCCUPIED =
            "本地已存在同编号的简历版本（属于另一条记录），本次不导入，本地数据未被改动";

    /** 计划条目：顶层记录与嵌套记录同构，子记录通过 {@code parent} 指向父条目。 */
    record Entry(ImportCollectionRegistry.ImportCollection collection,
                 ImportCollectionRegistry.ChildCollection child,
                 String stableId,
                 DesktopImportService.Decision decision,
                 Long localId,
                 String reason,
                 Map<String, Object> record,
                 Map<String, Object> localSnapshot,
                 Entry parent) {

        /** 面向用户的集合路径：{@code resumes} / {@code resumes.versions}。 */
        String collectionPath() {
            return child == null ? collection.name() : collection.name() + "." + child.name();
        }

        /** 本地表名（与 desktop_import_map.entity_type 同口径）。 */
        String table() {
            return child == null ? collection.table() : child.table();
        }

        /** idMap 的键前缀：父记录用集合名，子记录用子集合名。 */
        String relationName() {
            return child == null ? collection.name() : child.name();
        }

        String idMapKey() {
            return relationName() + ":" + record.get("id");
        }
    }

    record Plan(String packageId, List<String> unsupportedCollections, List<Entry> entries) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ImportContentComparator comparator;

    DesktopImportPlanner(JdbcTemplate jdbcTemplate, ImportContentComparator comparator) {
        this.jdbcTemplate = jdbcTemplate;
        this.comparator = comparator;
    }

    Plan plan(Map<String, Object> pkg) {
        validatePackage(pkg);
        Map<String, Object> manifest = asMap(pkg.get("manifest"));
        String packageId = String.valueOf(manifest.getOrDefault("packageId", "unknown"));

        List<Entry> entries = new ArrayList<>();
        Set<String> seenStableIds = new LinkedHashSet<>();
        for (ImportCollectionRegistry.ImportCollection collection : ImportCollectionRegistry.collections()) {
            for (Map<String, Object> record : recordsOf(pkg.get(collection.name()))) {
                Entry parent = classify(collection, null, null, record, seenStableIds);
                entries.add(parent);
                for (ImportCollectionRegistry.ChildCollection child : collection.children()) {
                    for (Map<String, Object> childRecord : recordsOf(record.get(child.name()))) {
                        entries.add(classify(collection, child, parent, childRecord, seenStableIds));
                    }
                }
            }
        }
        return new Plan(packageId, unsupportedCollections(pkg), List.copyOf(entries));
    }

    // ------------------------------------------------------------------ 分类

    private Entry classify(ImportCollectionRegistry.ImportCollection collection,
                           ImportCollectionRegistry.ChildCollection child,
                           Entry parent,
                           Map<String, Object> record,
                           Set<String> seenStableIds) {
        String stableId = stableIdOf(record);
        String path = child == null ? collection.name() : collection.name() + "." + child.name();
        if (stableId == null) {
            throw new IllegalStateException("包内记录缺少 stableId（集合 " + path
                    + "）—— 无法判断是否重复导入，已拒绝整份导入");
        }
        if (!seenStableIds.add(stableId)) {
            throw new IllegalStateException("包内稳定 ID 重复：" + stableId
                    + " —— 无法判断以哪条为准，已拒绝整份导入");
        }

        String table = child == null ? collection.table() : child.table();
        Long localId = localIdOf(stableId);
        Map<String, Object> local = localId == null ? Map.of() : loadLocal(table, localId);

        // 映射悬空（行已被留存清扫/手工删除）时同样按新增处理：否则「映射 + 行」缺一就永久无法再导入
        if (localId == null || local.isEmpty()) {
            return classifyAsAdd(collection, child, parent, record, stableId, table);
        }
        boolean same = comparator.same(table, local, record);
        return new Entry(collection, child, stableId,
                same ? DesktopImportService.Decision.SKIP : DesktopImportService.Decision.CONFLICT,
                localId,
                same ? "本地已有且内容一致，将跳过" : "本地已有但内容不同，需要你选择保留哪一版",
                record, local, parent);
    }

    private Entry classifyAsAdd(ImportCollectionRegistry.ImportCollection collection,
                                ImportCollectionRegistry.ChildCollection child,
                                Entry parent,
                                Map<String, Object> record,
                                String stableId,
                                String table) {
        Long occupying = "resume_version".equals(table) ? occupiedVersionId(parent, record) : null;
        if (occupying != null) {
            log.warn("导入：简历版本 {} 的 versionNo={} 与本地版本 id={} 撞号，本条跳过",
                    stableId, record.get("versionNo"), occupying);
            return new Entry(collection, child, stableId, DesktopImportService.Decision.SKIP,
                    null, REASON_VERSION_NO_OCCUPIED, record, null, parent);
        }
        return new Entry(collection, child, stableId, DesktopImportService.Decision.ADD,
                null, "本地没有这条记录，将新增", record, null, parent);
    }

    /**
     * 版本号是否已被本地占用（{@code uk_resume_version_no (resume_id, version_no)}，V1:92）。
     *
     * <p>刻意**不加** {@code deleted_at IS NULL}：唯一键不含软删标记，软删行同样占号；
     * 加了过滤会让预检说「空」而 INSERT 撞约束，整批回滚。
     *
     * <p>父记录本身是新增时不检查：新简历下不可能有旧版本。
     */
    private Long occupiedVersionId(Entry parent, Map<String, Object> record) {
        if (parent == null || parent.localId() == null) {
            return null;
        }
        Object versionNo = record.get("versionNo");
        if (versionNo == null) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM resume_version WHERE resume_id = ? AND version_no = ?",
                Long.class, parent.localId(), versionNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ------------------------------------------------------------------ 包校验

    private void validatePackage(Map<String, Object> pkg) {
        if (pkg == null || pkg.isEmpty()) {
            throw new IllegalStateException("导入包为空");
        }
        Object version = pkg.get("formatVersion");
        if (version == null) {
            throw new IllegalStateException("导入包缺少 formatVersion —— 无法确认格式是否受支持，已拒绝");
        }
        int value = version instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(version));
        if (value > 3) {
            throw new IllegalStateException("导入包格式版本 " + value + " 高于本版支持的 3，请升级桌面端");
        }
        if (pkg.get("manifest") == null) {
            throw new IllegalStateException("导入包缺少 manifest（缺少稳定 ID 清单，无法判断重复导入与冲突），已拒绝");
        }
    }

    /** 包里出现但本版不支持的集合（必须让用户知道，而不是静默丢掉）。 */
    private List<String> unsupportedCollections(Map<String, Object> pkg) {
        List<String> unsupported = new ArrayList<>();
        for (String key : pkg.keySet()) {
            if (!ImportCollectionRegistry.isKnownKey(key) && pkg.get(key) instanceof Collection<?> collection
                    && !collection.isEmpty()) {
                unsupported.add(key);
            }
        }
        return unsupported;
    }

    private List<Map<String, Object>> recordsOf(Object value) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = (Map<String, Object>) raw;
                    records.add(record);
                }
            }
        }
        return records;
    }

    private String stableIdOf(Map<String, Object> record) {
        Object value = record.get("stableId");
        return value == null ? null : String.valueOf(value);
    }

    private Long localIdOf(String stableId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT local_id FROM desktop_import_map WHERE source_stable_id = ?",
                (rs, rowNum) -> rs.getLong(1), stableId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> loadLocal(String table, Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            return map;
        }
        return Map.of();
    }
}
