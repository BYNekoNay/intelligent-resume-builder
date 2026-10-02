package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据字典 ↔ Flyway 迁移 门禁（静态）（第五十五批）。
 *
 * <p>背景：{@code docs/04-数据库设计说明书.md} 的 §3「表结构设计」是**面向评审与答辩的 schema 契约**，
 * 但此前没有任何门禁保证它与 {@code server/src/main/resources/db/migration/} 的实际 DDL 一致。
 * 第五十五批对账实测出两类漂移：
 * <ol>
 *   <li><b>文档漏写已实现的表</b>：{@code personal_profile} / {@code communication_draft} /
 *       {@code communication_template} / {@code inline_optimization_record} / {@code interview_ai_attempt} /
 *       {@code interview_asset_section} 共 6 张表已由 V3/V13/V17/V20/V23/V25 建出，§3 无章节；</li>
 *   <li><b>文档写了不存在的表</b>：{@code application_status_history} 在 {@code V1~V34} 中**无 CREATE TABLE**、
 *       代码亦无引用（设计草案）；另一处是命名漂移 —— 早年写作 {@code material_resume_task}，
 *       迁移里实为 {@code material_resume_generation}。</li>
 * </ol>
 *
 * <p>两个方向拆成两个用例，使失败信息各自聚焦（同一方法内的首个断言失败会掩盖第二个）。
 */
class SchemaDocContractTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?");

    /** §3 的表章节标题，如 {@code ### 3.12 application_status_history（**设计草案，未实现**）}。 */
    private static final Pattern DOC_TABLE_SECTION =
            Pattern.compile("(?m)^###\\s+3\\.\\d+(?:\\.\\d+)?\\s+([a-z_][a-z0-9_]*)([^\\n]*)");

    @Test
    @DisplayName("Flyway 迁移建的每张表都在 docs/04 §3 有章节")
    void migratedTablesAreDocumented() throws Exception {
        Set<String> migrated = migratedTables();
        Set<String> documented = documentedTables().keySet();

        assertTrue(migrated.size() >= 20,
                "从迁移中解析到的表过少（" + migrated.size() + "），门禁可能未生效");
        assertTrue(documented.size() >= 20,
                "从 docs/04 §3 解析到的表章节过少（" + documented.size() + "），门禁可能未生效");

        List<String> missingInDoc = new ArrayList<>();
        for (String table : migrated) {
            if (!documented.contains(table)) {
                missingInDoc.add("  " + table);
            }
        }
        assertTrue(missingInDoc.isEmpty(),
                "以下表已在 Flyway 迁移中建立，但 docs/04 §3「表结构设计」没有对应章节 —— "
                        + "数据字典漏写已实现的表（评审/答辩会用这份文档核对 schema）：\n"
                        + String.join("\n", missingInDoc));
    }

    @Test
    @DisplayName("docs/04 §3 的表章节必须对应迁移表，或显式标注「未实现 / 设计草案」")
    void documentedTablesExistOrAreMarkedUnimplemented() throws Exception {
        Set<String> migrated = migratedTables();
        Map<String, Boolean> documented = documentedTables();

        List<String> missingInMigration = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : documented.entrySet()) {
            if (!migrated.contains(entry.getKey()) && !entry.getValue()) {
                missingInMigration.add("  " + entry.getKey());
            }
        }
        assertTrue(missingInMigration.isEmpty(),
                "以下表在 docs/04 §3 有章节，但 V1~V34 迁移中**不存在**（也无代码引用）—— "
                        + "要么补迁移，要么在该章节标题显式标注「（设计草案，未实现）」并登记 OPEN-DECISIONS，"
                        + "不允许含糊地保留（含糊的文档会让评审误以为该表已存在）：\n"
                        + String.join("\n", missingInMigration));
    }

    private Set<String> migratedTables() throws Exception {
        Set<String> migrated = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(repoFile("server/src/main/resources/db/migration"))) {
            for (Path sql : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                Matcher matcher = CREATE_TABLE.matcher(SourceText.read(sql));
                while (matcher.find()) {
                    migrated.add(matcher.group(1).toLowerCase());
                }
            }
        }
        return migrated;
    }

    /** §3 表章节 → 是否显式标注「未实现 / 设计草案」。 */
    private Map<String, Boolean> documentedTables() throws Exception {
        String doc = SourceText.read(repoFile("docs/04-数据库设计说明书.md"));
        Map<String, Boolean> documented = new LinkedHashMap<>();
        Matcher matcher = DOC_TABLE_SECTION.matcher(doc);
        while (matcher.find()) {
            String suffix = matcher.group(2);
            boolean unimplemented = suffix.contains("未实现") || suffix.contains("设计草案");
            documented.merge(matcher.group(1).toLowerCase(), unimplemented, (a, b) -> a || b);
        }
        return documented;
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
