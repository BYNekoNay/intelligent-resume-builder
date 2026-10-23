package com.intelligentresume.desktop;

import java.util.List;
import java.util.Optional;

/**
 * 桌面导入器**唯一**的集合清单（ADR-012 阶段四 / R37 / R38）。
 *
 * <p><b>为什么要有这个类</b>（2026-10-23 实测缺陷）：原先同一件事实在
 * {@code DesktopImportService} 里维护了三份、而且互相矛盾 ——
 * {@code SUPPORTED_ORDER} 列了 3 类、{@code SUPPORTED_TABLES} 列了 4 类、
 * {@code unsupportedCollections} 又拿后者的 keySet 当「已知集合」。
 * 结果 {@code careerMaterials} 处在「有表名但不在遍历顺序里」的夹缝：
 * <b>既不参与预览、也不写入、还不算「不支持」</b> —— 用户的职业资料被静默丢弃，
 * 而 {@code AccountExportService} 明明把它导出了。
 *
 * <p>现在 preview / apply / unsupported 三处都只读这一份清单，
 * 契约门禁 {@code ImportCollectionContractTest} 还会断言它与
 * {@code AccountExportService} 的导出集合**精确相等** —— 少一类或漏一类都红。
 *
 * <p><b>声明顺序即依赖顺序</b>：先岗位与资料（无外键依赖），再简历（含版本），最后投递（引用岗位与版本）。
 */
final class ImportCollectionRegistry {

    /** 嵌套子集合：父记录里的数组 → 本地表 + 指向父记录的外键列。 */
    record ChildCollection(String name, String table, String foreignKey) {
    }

    /** 一条可导入的导出集合：导出集合名 → 本地表。 */
    record ImportCollection(String name, String table, List<ChildCollection> children) {
    }

    private static final List<ImportCollection> COLLECTIONS = List.of(
            new ImportCollection("jobDescriptions", "job_description", List.of()),
            new ImportCollection("careerMaterials", "career_material", List.of()),
            new ImportCollection("resumes", "resume",
                    List.of(new ChildCollection("versions", "resume_version", "resume_id"))),
            new ImportCollection("applications", "application_record",
                    List.of(new ChildCollection("statusHistory", "application_status_history",
                            "application_record_id"))));

    /** 包里除业务集合外允许出现的顶层键（元数据），不算「不支持的集合」。 */
    private static final List<String> KNOWN_METADATA_KEYS = List.of(
            "account", "personalProfile", "manifest", "formatVersion", "exportedAt", "source");

    private ImportCollectionRegistry() {
    }

    static List<ImportCollection> collections() {
        return COLLECTIONS;
    }

    static Optional<ImportCollection> byName(String name) {
        return COLLECTIONS.stream().filter(collection -> collection.name().equals(name)).findFirst();
    }

    /** 判断「包里这个顶层键是否是本版认识的」—— 不认识且非空的集合要被报出来，不能静默丢。 */
    static boolean isKnownKey(String key) {
        return byName(key).isPresent() || KNOWN_METADATA_KEYS.contains(key);
    }
}
