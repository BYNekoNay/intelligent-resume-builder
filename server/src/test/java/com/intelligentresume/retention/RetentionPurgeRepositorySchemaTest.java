package com.intelligentresume.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分档清扫**手写 SQL** 的 schema 守卫（决策 D2 阶段 1）。
 *
 * <p>三条用例针对手写 SQL 的三种失败模式：
 * <ol>
 *   <li><b>列名/表名写错</b> —— 编译期无保护，只在运行时炸 → {@link #purgeCandidateQueriesRunAgainstRealSchema()}；</li>
 *   <li><b>引用清单漏项</b> —— 更危险：漏掉一个引用者，就会把**被引用的行**当无引用者物理删除，
 *       造成不可逆的数据损坏 → {@link #purgeSqlCoversEveryForeignKey()}；</li>
 *   <li><b>删除语句丢掉软删限定</b> —— 候选查询被改坏时会删到**未软删的活数据** →
 *       {@link #deleteStatementsNeverTouchLiveRows()}。</li>
 * </ol>
 *
 * <p><b>为什么第 2 条用「静态解析迁移」而不是数据库元数据</b>（实测教训）：
 * 初版用 `SELECT DISTINCT TABLE_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
 * WHERE REFERENCED_TABLE_NAME = ?`。该列**只存在于 MySQL**，H2 2.2 没有它，而且
 * 这个类当时**漏了 `@ActiveProfiles("test")`** ⇒ 它连的是默认 MySQL 数据源：
 * **本机恰好有 MySQL，于是"全绿"；CI 没有 MySQL，直接 `Communications link failure`。**
 * 也就是说，一个"永远通过"的门禁只在有本地数据库时才为真 —— 正是最危险的那种。
 * 现在改为解析 `db/migration/*.sql`（Flyway 是本仓库 schema 的唯一来源，
 * `ddl-auto: none`）：结果与运行环境**完全无关**，且"漏一项即红"的能力不变。
 * 同时保留第 1 条在真实 schema（`test` profile 的 H2）上执行候选 SQL —— 那条验的是
 * 「表名/列名与引用清单一致」的运行期事实，与第 2 条的静态完整性互补。
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionPurgeRepositorySchemaTest {

    /** 从候选 SQL 中提取被提及的表名（本仓库的候选 SQL 一律写成 {@code FROM <table> t}）。 */
    private static final Pattern MENTIONED_TABLE =
            Pattern.compile("FROM\\s+([a-z_]+)\\s+t\\b", Pattern.CASE_INSENSITIVE);

    /** 迁移语句里被引用的目标表：{@code REFERENCES <table> (}。 */
    private static final Pattern FK_REFERENCE =
            Pattern.compile("REFERENCES\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 迁移语句的主角表：{@code CREATE TABLE <name>} / {@code ALTER TABLE <name>}。 */
    private static final Pattern STATEMENT_TABLE =
            Pattern.compile("(?:CREATE|ALTER)\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?",
                    Pattern.CASE_INSENSITIVE);

    @Autowired
    private RetentionPurgeRepository repository;

    @Test
    @DisplayName("候选 SQL 能在真实 schema 上执行（表名/列名与引用清单一致）")
    void purgeCandidateQueriesRunAgainstRealSchema() {
        LocalDateTime cutoff = LocalDateTime.now();

        assertDoesNotThrow(() -> assertNotNull(repository.findPurgeableResumeVersions(cutoff, 10)),
                "resume_version 的引用判定 SQL 必须能在真实 schema 上执行（含 11 处 NOT EXISTS）");
        assertDoesNotThrow(() -> assertNotNull(repository.findPurgeableCareerMaterials(cutoff, 10)),
                "career_material 的引用判定 SQL 必须能在真实 schema 上执行");
    }

    @Test
    @DisplayName("引用清单完整性：候选 SQL 覆盖迁移里所有指向该资源的外键（漏判会误删，不可逆）")
    void purgeSqlCoversEveryForeignKey() throws Exception {
        assertCoverage("resume_version", RetentionPurgeRepository.PURGEABLE_RESUME_VERSIONS);
        assertCoverage("career_material", RetentionPurgeRepository.PURGEABLE_CAREER_MATERIALS);
    }

    @Test
    @DisplayName("删除语句带 deleted_at 二次保护：候选查询被改坏也不会删到未软删的活数据")
    void deleteStatementsNeverTouchLiveRows() {
        assertGuardedDelete("resume_version", RetentionPurgeRepository.DELETE_RESUME_VERSION);
        assertGuardedDelete("career_material", RetentionPurgeRepository.DELETE_CAREER_MATERIAL);
    }

    @Test
    @DisplayName("快照语句同样带 id + deleted_at 双重限定：不会改到活数据，也不做无界更新")
    void snapshotStatementsAreNarrowlyScoped() {
        assertGuardedUpdate("resume_version", RetentionPurgeRepository.SNAPSHOT_RESUME_VERSION);
        assertGuardedUpdate("career_material", RetentionPurgeRepository.SNAPSHOT_CAREER_MATERIAL);
    }

    @Test
    @DisplayName("A 档与 B 档是同一份引用清单的两种极性（防两处清单漂移）")
    void purgeAndSnapshotPolaritiesCoverTheSameReferencers() {
        assertSameReferencers("resume_version",
                RetentionPurgeRepository.PURGEABLE_RESUME_VERSIONS,
                RetentionPurgeRepository.SNAPSHOTTABLE_RESUME_VERSIONS);
        assertSameReferencers("career_material",
                RetentionPurgeRepository.PURGEABLE_CAREER_MATERIALS,
                RetentionPurgeRepository.SNAPSHOTTABLE_CAREER_MATERIALS);
    }

    /** 两条语句必须提及**完全相同**的引用者集合 —— 漏一边就会把某类行同时判成 A 与 B（或都不判）。 */
    private void assertSameReferencers(String resource, String purgeable, String snapshottable) {
        Set<String> inPurgeable = mentionedTables(purgeable);
        Set<String> inSnapshottable = mentionedTables(snapshottable);
        assertTrue(inPurgeable.size() >= 2,
                resource + " 的候选查询提及的表过少（" + inPurgeable + "），解析可能已失效");
        assertEquals(inPurgeable, inSnapshottable,
                resource + " 的 A 档（NOT EXISTS）与 B 档（EXISTS）引用了**不同的**表集合 —— "
                        + "两者必须是同一份清单的两种极性，否则会有行既不被删也不被快照（或反之）：\n"
                        + "  仅 A 档有：" + diff(inPurgeable, inSnapshottable) + "\n"
                        + "  仅 B 档有：" + diff(inSnapshottable, inPurgeable));
    }

    private Set<String> mentionedTables(String sql) {
        Set<String> mentioned = new LinkedHashSet<>();
        Matcher matcher = MENTIONED_TABLE.matcher(sql);
        while (matcher.find()) {
            mentioned.add(matcher.group(1).toLowerCase());
        }
        return mentioned;
    }

    private Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> only = new LinkedHashSet<>(a);
        only.removeAll(b);
        return only;
    }

    /** 快照语句必须形如 {@code UPDATE <table> SET ... WHERE id = ? AND deleted_at IS NOT NULL}。 */
    private void assertGuardedUpdate(String table, String sql) {
        Pattern guarded = Pattern.compile(
                "UPDATE\\s+" + Pattern.quote(table) + "\\s+SET\\s+[\\s\\S]+\\sWHERE\\s+id\\s*=\\s*\\?"
                        + "\\s+AND\\s+deleted_at\\s+IS\\s+NOT\\s+NULL",
                Pattern.CASE_INSENSITIVE);
        assertTrue(guarded.matcher(sql.trim()).matches(),
                "快照 " + table + " 的语句必须同时限定 id 与 deleted_at IS NOT NULL——"
                        + "否则候选查询一旦被改坏就会**改写活数据**（比误删更隐蔽）：\n" + sql);
    }

    /** 候选 SQL 必须提及（作为 {@code NOT EXISTS} 子查询）迁移里每一个引用 {@code resource} 的表。 */
    private void assertCoverage(String resource, String sql) throws Exception {
        Set<String> referencing = tablesReferencing(resource);
        assertFalse(referencing.isEmpty(),
                "解析迁移后查不到任何引用 " + resource + " 的外键 —— 本用例的解析方式可能已失效，"
                        + "此时它会永远通过（比失败更危险），故显式失败");

        Set<String> mentioned = new LinkedHashSet<>();
        Matcher matcher = MENTIONED_TABLE.matcher(sql);
        while (matcher.find()) {
            mentioned.add(matcher.group(1).toLowerCase());
        }

        Set<String> missing = new LinkedHashSet<>(referencing);
        missing.removeAll(mentioned);

        assertTrue(missing.isEmpty(),
                "候选 SQL 没有把以下引用表纳入 NOT EXISTS —— 它们的外键指向 " + resource
                        + "，漏判会让**被引用的行**被当作无引用者物理删除（不可逆）：" + missing
                        + "（SQL 中已出现：" + mentioned + "；迁移中引用者：" + referencing + "）");
    }

    /** 扫全部 Flyway 迁移，返回所有「语句主角表」中通过 FOREIGN KEY 引用 {@code resource} 的表名。 */
    private Set<String> tablesReferencing(String resource) throws Exception {
        Set<String> referencing = new LinkedHashSet<>();
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        assertTrue(migrations.length > 0, "找不到 db/migration/*.sql —— 门禁必须在仓库内运行");

        for (Resource migration : migrations) {
            String sql;
            try (InputStream in = migration.getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // 去行注释后再按语句切分：FK 与被引用的表名可能不在同一条语句里（CREATE 与 ALTER 都有）
            for (String statement : sql.replaceAll("(?m)--[^\\n]*", "").split(";")) {
                Matcher table = STATEMENT_TABLE.matcher(statement);
                if (!table.find()) {
                    continue;
                }
                String referencingTable = table.group(1).toLowerCase();
                Matcher fk = FK_REFERENCE.matcher(statement);
                while (fk.find()) {
                    if (fk.group(1).equalsIgnoreCase(resource)) {
                        referencing.add(referencingTable);
                    }
                }
            }
        }
        return referencing;
    }

    /** 删除语句必须形如 {@code DELETE FROM <table> WHERE id = ? AND deleted_at IS NOT NULL}。 */
    private void assertGuardedDelete(String table, String sql) {
        Pattern guarded = Pattern.compile(
                "DELETE\\s+FROM\\s+" + Pattern.quote(table)
                        + "\\s+WHERE\\s+id\\s*=\\s*\\?\\s+AND\\s+deleted_at\\s+IS\\s+NOT\\s+NULL",
                Pattern.CASE_INSENSITIVE);
        assertTrue(guarded.matcher(sql.trim()).matches(),
                "删除 " + table + " 的语句必须同时限定 id 与 deleted_at IS NOT NULL——"
                        + "这是候选查询被改坏时**不误删活数据**的最后一道保护，缺失即不可逆：\n" + sql);
    }
}
