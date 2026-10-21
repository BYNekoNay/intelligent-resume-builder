package com.intelligentresume.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.config.DesktopLocalUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面端的 **Web → 桌面工作区迁移**（ADR-012 阶段四 / R27–R29 / R37–R38 / F5）。
 *
 * <p>核心判据（R37）：**只按稳定记录 ID 与归一化内容比对**，绝不按文本相似度自动合并 ——
 * 「不同 ID 即便内容很像，也是两条独立记录」。因此本类不引入任何模糊匹配。
 *
 * <p>R38 的顺序是硬约束：**先校验 → 再预览（新增/跳过/冲突）→ 用户确认 → 原子应用**。
 * 取消 = 不调用 apply，工作区一个字节都不动。
 *
 * <p>写入的关键是**主键重映射与外键链**：包里的记录带的是 Web 侧主键，
 * 而本地库的主键是自增的，且投递记录引用的是 Web 侧的 JD / 简历版本 ID。
 * 因此导入按依赖顺序（岗位 → 简历 → 版本 → 投递 → 状态历史）执行，
 * 用 {@code idMap} 把「Web 主键」映射成「本地主键」，并逐级改写外键。
 */
@Service
public class DesktopImportService {

    private static final Logger log = LoggerFactory.getLogger(DesktopImportService.class);

    /** 导入顺序 = 依赖顺序：先岗位，再简历（含版本），最后投递（含状态历史）。 */
    private static final List<String> SUPPORTED_ORDER = List.of(
            "jobDescriptions", "resumes", "applications");

    /** 导出集合 → 本地表名。 */
    private static final Map<String, String> SUPPORTED_TABLES = Map.of(
            "jobDescriptions", "job_description",
            "resumes", "resume",
            "careerMaterials", "career_material",
            "applications", "application_record");

    /** 嵌套集合：父集合 → (子集合, 子表, 外键列)。 */
    private static final Map<String, List<String[]>> CHILD_COLLECTIONS = Map.<String, List<String[]>>of(
            "resumes", List.<String[]>of(new String[]{"versions", "resume_version", "resume_id"}),
            "applications", List.<String[]>of(new String[]{"statusHistory", "application_status_history", "application_record_id"}));

    /** 归一化比对时要忽略的字段（R37：内容没变就不该算冲突，时间戳变了不算内容变化）。 */
    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "id", "stableId", "userId", "createdAt", "updatedAt", "deletedAt", "version");

    /**
     * 嵌套集合字段：它们**不是父表的列**，而是子记录（简历版本 / 投递状态历史）。
     * 比较父记录时必须跳过 —— 否则本地永远取不到这个键，每条记录都会被判成冲突。
     * 子记录各自带 stableId，由它们自己的比较负责。
     */
    private static final Set<String> NESTED_FIELDS = Set.of("versions", "statusHistory");

    /**
     * 引用类字段：包里存的是 **Web 侧主键**，本地存的是**本地主键**，值本来就不相等。
     * 它们描述的是「指向谁」，而不是「内容是什么」—— 对应关系由 stableId 映射保证，
     * 所以**不参与内容比较**（否则每条记录都会被判成冲突）。
     */
    private static final Set<String> REFERENCE_FIELDS = Set.of(
            "currentVersionId", "jobDescriptionId", "resumeId", "resumeVersionId",
            "createdBy", "parentTaskId", "resultResumeVersionId");

    /** 插入时必须跳过的字段：自增主键、稳定 ID、乐观锁版本（本地从 0 起算）。 */
    private static final Set<String> SKIPPED_ON_INSERT = Set.of("id", "stableId", "version");

    private final JdbcTemplate jdbcTemplate;
    private final DesktopLocalUserService localUserService;
    private final ObjectMapper objectMapper;

    public DesktopImportService(JdbcTemplate jdbcTemplate,
                                DesktopLocalUserService localUserService,
                                ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.localUserService = localUserService;
        this.objectMapper = objectMapper;
    }

    /** 一条记录的预览结论。 */
    public enum Decision {
        /** 本地没有 → 新增 */
        ADD,
        /** 本地已有且内容一致 → 跳过 */
        SKIP,
        /** 本地已有但内容变了 → 冲突，交给用户解决 */
        CONFLICT
    }

    public record PreviewItem(String collection,
                              String stableId,
                              Decision decision,
                              String entityType,
                              Long localId,
                              String reason,
                              Map<String, Object> localSnapshot,
                              Map<String, Object> importedSnapshot) {
    }

    public record ImportPreview(String packageId,
                                int added,
                                int skipped,
                                int conflicts,
                                List<String> unsupportedCollections,
                                List<PreviewItem> items) {
    }

    public record ImportResult(int inserted,
                               int updated,
                               int skipped,
                               int keptLocal,
                               List<String> insertedStableIds) {
    }

    // ------------------------------------------------------------------ 预览

    /**
     * 校验并分类，**不写任何数据**（R38：写入前必须先预览）。
     */
    public ImportPreview preview(Map<String, Object> pkg) {
        validatePackage(pkg);
        Map<String, Object> manifest = asMap(pkg.get("manifest"));
        String packageId = String.valueOf(manifest.getOrDefault("packageId", "unknown"));

        List<PreviewItem> items = new ArrayList<>();
        for (String collection : SUPPORTED_ORDER) {
            String table = SUPPORTED_TABLES.get(collection);
            for (Map<String, Object> record : recordsOf(pkg.get(collection))) {
                String stableId = stableIdOf(record);
                if (stableId == null) {
                    throw new IllegalStateException(
                            "包内记录缺少 stableId（集合 " + collection + "）—— 无法判断是否重复导入，已拒绝整份导入");
                }
                Long localId = localIdOf(stableId);
                if (localId == null) {
                    items.add(new PreviewItem(collection, stableId, Decision.ADD, table, null,
                            "本地没有这条记录，将新增", null, record));
                    continue;
                }
                Map<String, Object> local = loadLocal(table, localId);
                boolean same = sameContent(local, record);
                items.add(new PreviewItem(collection, stableId,
                        same ? Decision.SKIP : Decision.CONFLICT, table, localId,
                        same ? "本地已有且内容一致，将跳过" : "本地已有但内容不同，需要你选择保留哪一版",
                        local, record));
            }
        }

        int added = (int) items.stream().filter(i -> i.decision() == Decision.ADD).count();
        int skipped = (int) items.stream().filter(i -> i.decision() == Decision.SKIP).count();
        int conflicts = (int) items.stream().filter(i -> i.decision() == Decision.CONFLICT).count();
        return new ImportPreview(packageId, added, skipped, conflicts,
                unsupportedCollections(pkg), List.copyOf(items));
    }

    // ------------------------------------------------------------------ 应用

    /**
     * 原子应用导入（R38：确认后一次性写入；中途失败整体回滚）。
     *
     * @param resolutions 每个稳定 ID 的解决方式：
     *                    {@code "KEEP_LOCAL"} / {@code "USE_IMPORTED"}，
     *                    或一个「字段 → 值」的 Map（手动合并，只改这些字段）
     */
    @Transactional
    public ImportResult apply(Map<String, Object> pkg, Map<String, Object> resolutions) {
        ImportPreview preview = preview(pkg);
        if (!preview.unsupportedCollections().isEmpty()) {
            throw new IllegalStateException("包内含本版不支持的集合：" + preview.unsupportedCollections()
                    + " —— 为避免静默丢数据，整份导入已被拒绝");
        }
        Map<String, Object> decisions = resolutions == null ? Map.of() : resolutions;

        // Web 主键 → 本地主键
        Map<String, Long> idMap = new LinkedHashMap<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        int keptLocal = 0;
        List<String> insertedIds = new ArrayList<>();

        for (String collection : SUPPORTED_ORDER) {
            String table = SUPPORTED_TABLES.get(collection);
            for (Map<String, Object> record : recordsOf(pkg.get(collection))) {
                String stableId = stableIdOf(record);
                PreviewItem item = findItem(preview, stableId);
                if (item == null) {
                    continue;
                }
                switch (item.decision()) {
                    case SKIP -> skipped++;
                    case ADD -> {
                        Long localId = insertRecord(table, record, idMap, null);
                        idMap.put(idMapKey(collection, record), localId);
                        rememberMapping(table, localId, stableId);
                        inserted++;
                        insertedIds.add(stableId);
                        insertChildren(collection, record, idMap);
                    }
                    case CONFLICT -> {
                        Object resolution = decisions.get(stableId);
                        if (resolution == null || "KEEP_LOCAL".equals(resolution)) {
                            keptLocal++;
                        } else if ("USE_IMPORTED".equals(resolution)) {
                            updateRecord(table, item.localId(), record, idMap);
                            updated++;
                        } else if (resolution instanceof Map<?, ?> merged) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mergedFields = (Map<String, Object>) merged;
                            updateRecord(table, item.localId(), mergedFields, idMap);
                            updated++;
                        } else {
                            keptLocal++;
                        }
                    }
                    default -> throw new IllegalStateException("未知决策 " + item.decision());
                }
            }
        }
        log.info("桌面工作区导入完成：新增 {}、更新 {}、跳过 {}、保留本地 {}", inserted, updated, skipped, keptLocal);
        return new ImportResult(inserted, updated, skipped, keptLocal, List.copyOf(insertedIds));
    }

    /** 插入嵌套记录（简历版本 / 投递状态历史），并把子记录的外键指向本地父 ID。 */
    private void insertChildren(String collection, Map<String, Object> parentRecord, Map<String, Long> idMap) {
        List<String[]> children = CHILD_COLLECTIONS.get(collection);
        if (children == null) {
            return;
        }
        for (String[] child : children) {
            String childCollection = child[0];
            String childTable = child[1];
            for (Map<String, Object> childRecord : recordsOf(parentRecord.get(childCollection))) {
                Long childLocalId = insertRecord(childTable, childRecord, idMap, parentRecord);
                idMap.put(idMapKey(childCollection, childRecord), childLocalId);
                rememberMapping(childTable, childLocalId, stableIdOf(childRecord));
                // 简历的 current_version_id 指向刚插入的版本（循环外键，只能事后回填）
                if ("resume_version".equals(childTable)) {
                    backfillCurrentVersion(collection, parentRecord, childLocalId);
                }
            }
        }
    }

    private void backfillCurrentVersion(String collection, Map<String, Object> parentRecord, Long versionLocalId) {
        Long parentLocalId = localIdOf(String.valueOf(parentRecord.get("stableId")));
        if (parentLocalId == null) {
            return;
        }
        jdbcTemplate.update("UPDATE " + SUPPORTED_TABLES.get(collection) + " SET current_version_id = ? WHERE id = ?",
                versionLocalId, parentLocalId);
    }

    private PreviewItem findItem(ImportPreview preview, String stableId) {
        for (PreviewItem item : preview.items()) {
            if (item.stableId().equals(stableId)) {
                return item;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部

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
        Set<String> known = new LinkedHashSet<>(SUPPORTED_TABLES.keySet());
        known.add("account");
        known.add("personalProfile");
        known.add("manifest");
        known.add("formatVersion");
        known.add("exportedAt");
        known.add("source");
        List<String> unsupported = new ArrayList<>();
        for (String key : pkg.keySet()) {
            if (!known.contains(key) && pkg.get(key) instanceof Collection<?> collection && !collection.isEmpty()) {
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

    /** idMap 的键：集合 + Web 主键。 */
    private String idMapKey(String collection, Map<String, Object> record) {
        return collection + ":" + record.get("id");
    }

    private Long localIdOf(String stableId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT local_id FROM desktop_import_map WHERE source_stable_id = ?",
                (rs, rowNum) -> rs.getLong(1), stableId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void rememberMapping(String entityType, Long localId, String stableId) {
        Long localUserId = localUserService.localUserId();
        jdbcTemplate.update("INSERT INTO desktop_import_map (user_id, entity_type, local_id, source_stable_id, imported_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                localUserId, entityType, localId, stableId);
    }

    /**
     * 判断「本地这条」与「包里这条」是不是同一份内容（R37）。
     *
     * <p>两个容易踩空的点：
     * <ul>
     *   <li>**只比较包里给出的字段**：本地表还有 id / user_id / 解析结果等包外字段，
     *       全量比较会让「内容其实一样」永远判成冲突；</li>
     *   <li>**字段名要规范化**：导出的是驼峰（{@code companyName}），
     *       本地表列是下划线（{@code company_name}）—— 不归一化就永远比不相等。</li>
     * </ul>
     */
    private boolean sameContent(Map<String, Object> local, Map<String, Object> imported) {
        // 本地记录先建「归一化视图」：列名是下划线，导出字段是驼峰，
        // 不建视图就 local.get("companyName") 取不到 company_name 这一列。
        Map<String, Object> localView = new LinkedHashMap<>();
        local.forEach((key, value) -> localView.put(normalizeKey(key), value));

        Map<String, Object> left = new LinkedHashMap<>();
        Map<String, Object> right = new LinkedHashMap<>();
        imported.forEach((key, value) -> {
            if (VOLATILE_FIELDS.contains(key) || "stableId".equals(key)
                    || NESTED_FIELDS.contains(key) || REFERENCE_FIELDS.contains(key)) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            // 本地表**没有这一列** → 该字段不可比（不是「值不同」）。
            // 例如投递记录的公司列在本地表里并不叫 companyName。
            // 判成冲突会误报，必须跳过。
            if (!localView.containsKey(normalizedKey)) {
                return;
            }
            right.put(normalizedKey, normalizeValue(value));
            left.put(normalizedKey, normalizeValue(localView.get(normalizedKey)));
        });
        return objectMapper.valueToTree(left).toString().equals(objectMapper.valueToTree(right).toString());
    }

    /** 字段名归一化：驼峰与下划线视作同一个字段。 */
    private String normalizeKey(String key) {
        return key.replace("_", "").toLowerCase();
    }

    /** 驼峰 → 下划线：包里的字段名要转成本地表列名才能插入。 */
    private String toSnakeCase(String key) {
        StringBuilder builder = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isUpperCase(ch)) {
                builder.append('_').append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> nested.put(String.valueOf(key), normalizeValue(nestedValue)));
            return new java.util.TreeMap<>(nested);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>();
            collection.forEach(element -> normalized.add(normalizeValue(element)));
            return normalized;
        }
        return value;
    }

    private Map<String, Object> loadLocal(String table, Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * 插入一条导入记录：自增主键由数据库分配，{@code user_id} 换成本地用户，
     * 外键值用父记录的本地 ID 替换。返回本地新 ID。
     */
    private Long insertRecord(String table, Map<String, Object> record, Map<String, Long> idMap,
                              Map<String, Object> parentRecord) {
        Map<String, Object> values = prepareValues(table, record, idMap, parentRecord);
        List<String> columns = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String column = entry.getKey();
            if (!localColumns(table).contains(column)) {
                continue;   // 包里可能有本地表没有的字段（如嵌套结构），跳过
            }
            columns.add(column);
            args.add(entry.getValue());
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("记录没有可导入的列（表 " + table + "）");
        }
        StringBuilder placeholderBuilder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                placeholderBuilder.append(',');
            }
            placeholderBuilder.append('?');
        }
        String placeholders = placeholderBuilder.toString();
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
        Map<String, Object> keys = keyHolder.getKeys();
        Object id = null;
        if (keys != null) {
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey())) {
                    id = entry.getValue();
                    break;
                }
            }
        }
        return id instanceof Number number ? number.longValue() : null;
    }

    private void updateRecord(String table, Long localId, Map<String, Object> source, Map<String, Long> idMap) {
        Map<String, Object> values = prepareValues(table, source, idMap, null);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String column = entry.getKey();
            if (!localColumns(table).contains(column) || column.equals("id")) {
                continue;
            }
            sets.add(column + " = ?");
            args.add(entry.getValue());
        }
        if (sets.isEmpty()) {
            return;
        }
        args.add(localId);
        jdbcTemplate.update("UPDATE " + table + " SET " + String.join(",", sets) + " WHERE id = ?",
                args.toArray());
    }

    /**
     * 把包里的记录整理成「可直接插入本地表」的值集合：
     * 键转成下划线列名、跳过自增主键与乐观锁版本、{@code user_id} 换成本地用户、外键改写为本地主键。
     */
    private Map<String, Object> prepareValues(String table, Map<String, Object> record, Map<String, Long> idMap,
                                              Map<String, Object> parentRecord) {
        Map<String, Object> values = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (SKIPPED_ON_INSERT.contains(key) || "userId".equals(key)) {
                return;
            }
            values.put(toSnakeCase(key), value);
        });
        Long localUserId = localUserService.localUserId();
        if (localUserId == null) {
            throw new IllegalStateException("本地用户尚未初始化，无法导入");
        }
        values.put("user_id", localUserId);

        // 外键改写：包里是 Web 侧主键，本地要用映射后的本地主键
        switch (table) {
            case "resume" -> {
                // 循环外键：resume.current_version_id 指向自己的版本行，
                // 而版本行此刻还不存在 —— 带着 Web 侧的值插入必然违反外键约束。
                // 先置空，等版本插入后再回填本地版本 ID（见 backfillCurrentVersion）。
                values.remove("current_version_id");
            }
            case "resume_version" -> {
                Long parent = parentRecord == null ? null
                        : idMap.get(idMapKey("resumes", parentRecord));
                if (parent != null) {
                    values.put("resume_id", parent);
                }
            }
            case "application_record" -> {
                replaceForeignKey(values, "job_description_id", idMap, "jobDescriptions");
                replaceForeignKey(values, "resume_version_id", idMap, "versions");
                replaceForeignKey(values, "resume_id", idMap, "resumes");
            }
            case "application_status_history" -> {
                Long parent = parentRecord == null ? null
                        : idMap.get(idMapKey("applications", parentRecord));
                if (parent != null) {
                    values.put("application_record_id", parent);
                }
            }
            default -> {
                // 无外键的表（岗位、职业资料）
            }
        }
        return values;
    }

    /** 用 idMap 把外键列里的 Web 主键换成本地主键（取不到就保留原值，由外键约束兜底）。 */
    private void replaceForeignKey(Map<String, Object> values, String column, Map<String, Long> idMap,
                                   String collection) {
        Object webId = values.get(column);
        if (webId == null) {
            return;
        }
        Long local = idMap.get(collection + ":" + webId);
        if (local != null) {
            values.put(column, local);
        }
    }

    /** 本地表列名缓存（用数据库元数据，避免硬编码列清单随 schema 漂移）。 */
    private final Map<String, Set<String>> columnCache = new LinkedHashMap<>();

    private Set<String> localColumns(String table) {
        return columnCache.computeIfAbsent(table, key -> {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE lower(table_name) = ?",
                    String.class, key.toLowerCase());
            // H2 的 information_schema 返回的列名是**大写**（TITLE / USER_ID），
            // 而包里的字段转成下划线后是小写 —— 不统一成小写就一列都匹配不上。
            Set<String> columns = new LinkedHashSet<>();
            names.forEach(name -> columns.add(name.toLowerCase()));
            return columns;
        });
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
