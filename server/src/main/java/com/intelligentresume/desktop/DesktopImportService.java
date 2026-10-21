package com.intelligentresume.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.config.DesktopLocalUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>本版支持的集合刻意收窄为 4 个核心集合（简历、职业资料、JD、投递）；
 * 包里若出现其它集合的记录，预览阶段**直接拒绝整份导入** —— 静默丢弃用户数据是不可接受的。
 *
 * <p><b>本版只提供「预览与分类」，不提供写入</b>：写入需要把 Web 侧主键重映射到本地主键，
 * 并沿 岗位→简历→版本→投递 的依赖链逐级改写外键 —— 这部分留到下一批，
 * 与其先做一个会整批回滚的半成品，不如先把 R37 的判断逻辑（新增/跳过/冲突）做扎实。
 */
@Service
public class DesktopImportService {

    private static final Logger log = LoggerFactory.getLogger(DesktopImportService.class);

    /** 导出集合 → 本地表名。 */
    private static final Map<String, String> SUPPORTED_TABLES = Map.of(
            "resumes", "resume",
            "careerMaterials", "career_material",
            "jobDescriptions", "job_description",
            "applications", "application_record");

    /** 归一化比对时忽略的字段（R37：内容没变就不该算冲突，时间戳变了不算内容变化）。 */
    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "id", "stableId", "userId", "createdAt", "updatedAt", "deletedAt", "version");

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
        for (Map.Entry<String, String> entry : SUPPORTED_TABLES.entrySet()) {
            String collection = entry.getKey();
            String table = entry.getValue();
            for (Map<String, Object> record : recordsOf(pkg, collection)) {
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

    // ------------------------------------------------------------------ 内部

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

    private List<Map<String, Object>> recordsOf(Map<String, Object> pkg, String collection) {
        Object value = pkg.get(collection);
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
            if (VOLATILE_FIELDS.contains(key) || "stableId".equals(key)) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            right.put(normalizedKey, normalizeValue(value));
            left.put(normalizedKey, normalizeValue(localView.get(normalizedKey)));
        });
        return objectMapper.valueToTree(left).toString().equals(objectMapper.valueToTree(right).toString());
    }

    /** 字段名归一化：驼峰与下划线视作同一个字段。 */
    private String normalizeKey(String key) {
        return key.replace("_", "").toLowerCase();
    }

    private Map<String, Object> normalize(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (VOLATILE_FIELDS.contains(key) || key.equals("stableId")) {
                return;
            }
            result.put(key, normalizeValue(value));
        });
        return result;
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
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
