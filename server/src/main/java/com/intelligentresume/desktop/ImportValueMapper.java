package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopLocalUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 包内记录 → 本地列值：驼峰转下划线、跳过不该写的列、{@code user_id}、以及**全部外键改写**。
 *
 * <h2>为什么外键规则要集中在一处</h2>
 * 原实现把这些规则写在 {@code prepareValues} 的 switch 里，只覆盖 4 张表的一部分，
 * 于是留下三个只在**真实跨用户包**上才会炸的缺口（批次 1.4 实测）：
 * <ul>
 *   <li>{@code resume.job_description_id}（V12 外键）从未重映射 → 岗位定制简历必然 FK 失败或挂错岗位；</li>
 *   <li>{@code resume_version.created_by} 是 {@code FK → user(id)}，包内是 **Web 用户 id** → 跨用户导入 FK 失败；</li>
 *   <li>{@code resume_version.restored_from_version_id}（V11 自引用）既未重映射也未跳过比较。</li>
 * </ul>
 * 既有用例都用 {@code createdBy=1}（恰好等于本地用户 id）与同 id 的父记录，所以长期全绿。
 */
final class ImportValueMapper {

    private static final Logger log = LoggerFactory.getLogger(ImportValueMapper.class);

    /** 自增主键、稳定 ID、乐观锁版本都不来自包内。 */
    private static final Set<String> SKIPPED_ON_INSERT = Set.of("id", "stableId", "version");

    private final ImportColumnBinder columnBinder;
    private final DesktopLocalUserService localUserService;

    ImportValueMapper(ImportColumnBinder columnBinder, DesktopLocalUserService localUserService) {
        this.columnBinder = columnBinder;
        this.localUserService = localUserService;
    }

    /**
     * @param entry  计划条目（提供表名与父记录，用于改写父外键）
     * @param record 要写入的字段来源：新增用包内记录，覆盖用包内记录或用户合并后的字段
     * @param idMap  Web 主键 → 本地主键
     */
    Map<String, Object> valuesFor(DesktopImportPlanner.Entry entry, Map<String, Object> record,
                                  Map<String, Long> idMap) {
        String table = entry.table();
        Map<String, Object> values = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (SKIPPED_ON_INSERT.contains(key) || "userId".equals(key)) {
                return;
            }
            String column = toSnakeCase(key);
            values.put(column, columnBinder.valueFor(table, column, value));
        });

        Long localUserId = localUserService.localUserId();
        if (localUserId == null) {
            throw new IllegalStateException("本地用户尚未初始化，无法导入");
        }
        values.put("user_id", localUserId);

        switch (table) {
            case "resume" -> {
                // 循环外键：先置空，等全部版本落库后由 writer 按包内 currentVersionId 统一回填
                values.remove("current_version_id");
                replaceNullable(values, "job_description_id", idMap, "jobDescriptions");
            }
            case "resume_version" -> {
                values.put("resume_id", requireParent(entry, idMap));
                // created_by 指向 user(id)：桌面工作区只有一个本地用户，包里的 Web 用户 id 本地并不存在
                values.put("created_by", localUserId);
                replaceNullable(values, "restored_from_version_id", idMap, "versions");
            }
            case "application_record" -> {
                replaceRequired(values, "job_description_id", idMap, "jobDescriptions", entry);
                replaceRequired(values, "resume_version_id", idMap, "versions", entry);
            }
            case "application_status_history" ->
                    values.put("application_record_id", requireParent(entry, idMap));
            default -> {
                // 其余表没有父外键，无需改写
            }
        }
        return values;
    }

    /** 父记录（简历 / 投递）的本地主键：必须已由 writer 播种，否则说明计划顺序被破坏。 */
    private Long requireParent(DesktopImportPlanner.Entry entry, Map<String, Long> idMap) {
        DesktopImportPlanner.Entry parent = entry.parent();
        Long parentId = parent == null ? null : idMap.get(parent.idMapKey());
        if (parentId == null) {
            throw new IllegalStateException("导入计划缺少父记录的本地主键（子记录 " + entry.stableId()
                    + "）—— 计划顺序被破坏，已拒绝写入");
        }
        return parentId;
    }

    /**
     * 可空引用：映射不到就置 {@code NULL}。
     *
     * <p>保留 Web id 的后果比 NULL 更糟：要么 FK 失败整批回滚，要么命中本地无关行造成**静默的错误关联**。
     */
    private void replaceNullable(Map<String, Object> values, String column, Map<String, Long> idMap, String prefix) {
        Object webId = values.remove(column);
        if (webId == null) {
            return;
        }
        Long localId = idMap.get(prefix + ":" + webId);
        if (localId == null) {
            log.warn("导入：{} 的引用 {}={} 在包内找不到对应记录，置空", column, prefix, webId);
            return;
        }
        values.put(column, localId);
    }

    /**
     * 必填引用：映射不到就抛。
     *
     * <p>投递的 {@code job_description_id} / {@code resume_version_id} 是 NOT NULL ——
     * 缺引用时「写半份」比整份拒绝更危险（用户会以为导入成功）。
     */
    private void replaceRequired(Map<String, Object> values, String column, Map<String, Long> idMap,
                                 String prefix, DesktopImportPlanner.Entry entry) {
        Object webId = values.remove(column);
        if (webId == null) {
            return;
        }
        Long localId = idMap.get(prefix + ":" + webId);
        if (localId == null) {
            throw new IllegalStateException("包内投递（" + entry.stableId() + "）引用的 " + prefix
                    + " id=" + webId + " 不在包内 —— 为避免挂错或写入半份数据，已拒绝整份导入");
        }
        values.put(column, localId);
    }

    /** 驼峰 → 下划线：包里的字段名要转成本地表列名才能插入。 */
    String toSnakeCase(String key) {
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
}
