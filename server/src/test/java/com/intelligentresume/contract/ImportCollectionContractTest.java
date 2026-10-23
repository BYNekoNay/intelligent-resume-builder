package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出集合 ↔ 导入集合 门禁（静态）。
 *
 * <p><b>为什么需要它</b>（2026-10-23 实测缺陷）：{@code DesktopImportService} 原先把同一件事实维护成三份 ——
 * 遍历顺序常量（3 类）、集合→表名常量（4 类）、以及「不支持集合」判定（拿后者当已知）。
 * {@code careerMaterials} 落在「有表名但不在遍历顺序」的夹缝里：
 * <b>既不预览、也不写入、还不会被判为不支持</b> → 用户的职业资料被静默丢弃，
 * 而 {@code AccountExportService} 明明把它导出了。更糟的是当时没有任何测试提到
 * {@code careerMaterial}，因此该路径零覆盖、长期全绿。
 *
 * <p>本门禁把「导入器认识的集合」与「导出器产出的集合」钉在一起，两个方向都查：
 * <ol>
 *   <li><b>不能少</b>：本版声明支持的 4 类核心集合必须全在（少一类直接红 —— 正是上面的缺陷形态）；</li>
 *   <li><b>不能编</b>：导入器写的集合名必须是导出器真实产出的名字（防拼错成 {@code careerMaterial}）；</li>
 *   <li><b>表必须存在</b>：每条映射的本地表必须能在 Flyway 迁移里找到 {@code CREATE TABLE}（防表名漂移）。</li>
 * </ol>
 *
 * <p>外部契约是 {@code AccountExportService} 的 {@code EXPORT_COLLECTIONS}（9 类），
 * 本版导入只支持其中 4 类核心集合，其余由 {@code unsupportedCollections} 显式报出并拒绝整份导入 ——
 * 「显式拒绝」可接受，「静默丢弃」不可接受。
 */
class ImportCollectionContractTest {

    /** 本版声明支持导入的核心集合（ADR-012 阶段四 / R37 的产品口径）。 */
    private static final Set<String> SUPPORTED_CORE = new TreeSet<>(List.of(
            "applications", "careerMaterials", "jobDescriptions", "resumes"));

    private static final Pattern EXPORT_COLLECTION =
            Pattern.compile("\"([a-zA-Z][a-zA-Z0-9]*)\"", Pattern.MULTILINE);

    /** {@code new ImportCollection("resumes", "resume", ...)} */
    private static final Pattern REGISTRY_ENTRY = Pattern.compile(
            "new\\s+ImportCollection\\(\\s*\"([a-zA-Z][a-zA-Z0-9]*)\"\\s*,\\s*\"([a-z_][a-z0-9_]*)\"");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?");

    @Test
    @DisplayName("导入器支持的集合 == 本版声明的 4 类核心集合（少一类即静默丢数据）")
    void importCollectionsMatchDeclaredCore() throws IOException {
        Set<String> actual = new TreeSet<>(registryEntries().keySet());
        assertEquals(SUPPORTED_CORE, actual,
                "导入集合与本版声明不一致。少一类 = 该集合既不预览也不写入（历史缺陷形态）；"
                        + "多一类 = 未经验证就宣称支持。实际：" + actual);
    }

    @Test
    @DisplayName("导入器写的集合名必须是导出器真实产出的名字（防拼错）")
    void importCollectionsExistInExport() throws IOException {
        Set<String> exported = exportCollections();
        assertTrue(!exported.isEmpty(), "没能从 AccountExportService 解析出导出集合，门禁自身失效");

        Set<String> unknown = new TreeSet<>(registryEntries().keySet());
        unknown.removeAll(exported);
        assertTrue(unknown.isEmpty(),
                "导入器写了导出器不会产出的集合名（拼错或已改名）：" + unknown + "；导出器实际产出：" + exported);
    }

    @Test
    @DisplayName("导入映射的本地表必须在 Flyway 迁移里真实存在（防表名漂移）")
    void importTablesExistInMigrations() throws IOException {
        Set<String> migrated = new TreeSet<>();
        Matcher matcher = CREATE_TABLE.matcher(migrationsText());
        while (matcher.find()) {
            migrated.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        assertTrue(!migrated.isEmpty(), "没能从迁移里解析出任何 CREATE TABLE，门禁自身失效");

        Set<String> mapped = new TreeSet<>(registryEntries().values());
        Set<String> missing = new TreeSet<>(mapped);
        missing.removeAll(migrated);
        assertTrue(missing.isEmpty(), "导入映射指向了迁移里不存在的表：" + missing);
        assertEquals(4, mapped.size(), "本版应映射 4 张本地表，实际：" + mapped);
    }

    // ------------------------------------------------------------------ 解析

    /** 集合名 → 本地表名。 */
    private java.util.Map<String, String> registryEntries() throws IOException {
        String source = SourceText.read(repoFile(
                "server/src/main/java/com/intelligentresume/desktop/ImportCollectionRegistry.java"));
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        Matcher matcher = REGISTRY_ENTRY.matcher(source);
        while (matcher.find()) {
            entries.put(matcher.group(1), matcher.group(2));
        }
        assertTrue(!entries.isEmpty(), "没能从 ImportCollectionRegistry 解析出任何集合，门禁自身失效");
        return entries;
    }

    /** {@code AccountExportService} 的 {@code EXPORT_COLLECTIONS} 字面量里的集合名。 */
    private Set<String> exportCollections() throws IOException {
        String source = SourceText.read(repoFile(
                "server/src/main/java/com/intelligentresume/auth/service/AccountExportService.java"));
        int start = source.indexOf("EXPORT_COLLECTIONS");
        assertTrue(start >= 0, "AccountExportService 里找不到 EXPORT_COLLECTIONS");
        int end = source.indexOf(')', start);
        assertTrue(end > start, "EXPORT_COLLECTIONS 字面量没有闭合");

        Set<String> names = new TreeSet<>();
        Matcher matcher = EXPORT_COLLECTION.matcher(source.substring(start, end));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private String migrationsText() throws IOException {
        StringBuilder builder = new StringBuilder();
        try (Stream<Path> files = Files.list(repoFile("server/src/main/resources/db/migration"))) {
            for (Path file : files.sorted().toList()) {
                builder.append(Files.readString(file)).append('\n');
            }
        }
        return builder.toString();
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件/目录: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
