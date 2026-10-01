package com.intelligentresume.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分档清扫**手写 SQL** 的真实 schema 校验（决策 D2 阶段 1）。
 *
 * <p>三条用例针对手写 SQL 的三种失败模式：
 * <ol>
 *   <li><b>列名/表名写错</b> —— 编译期无保护，只在运行时炸 → {@link #purgeCandidateQueriesRunAgainstRealSchema()}；</li>
 *   <li><b>引用清单漏项</b> —— 更危险：漏掉一个引用者，就会把**被引用的行**当无引用者物理删除，
 *       造成不可逆的数据损坏 → {@link #purgeSqlCoversEveryForeignKey()} 从 schema 元数据
 *       反查所有指向该资源的外键，与 SQL 里出现过的表逐一比对；</li>
 *   <li><b>删除语句丢掉软删限定</b> —— 候选查询被改坏时会删到**未软删的活数据** →
 *       {@link #deleteStatementsNeverTouchLiveRows()} 断言删除语句恒带 {@code deleted_at IS NOT NULL}。</li>
 * </ol>
 */
@SpringBootTest
class RetentionPurgeRepositorySchemaTest {

    /** 从 SQL 中提取被提及的表名（本仓库的候选 SQL 一律写成 {@code FROM <table> t}）。 */
    private static final Pattern MENTIONED_TABLE =
            Pattern.compile("FROM\\s+([a-z_]+)\\s+t\\b", Pattern.CASE_INSENSITIVE);

    @Autowired
    private RetentionPurgeRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

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
    @DisplayName("引用清单完整性：候选 SQL 覆盖所有指向该资源的外键（漏判会误删，不可逆）")
    void purgeSqlCoversEveryForeignKey() {
        assertCoverage("resume_version", RetentionPurgeRepository.PURGEABLE_RESUME_VERSIONS);
        assertCoverage("career_material", RetentionPurgeRepository.PURGEABLE_CAREER_MATERIALS);
    }

    @Test
    @DisplayName("删除语句带 deleted_at 二次保护：候选查询被改坏也不会删到未软删的活数据")
    void deleteStatementsNeverTouchLiveRows() {
        assertGuardedDelete("resume_version", RetentionPurgeRepository.DELETE_RESUME_VERSION);
        assertGuardedDelete("career_material", RetentionPurgeRepository.DELETE_CAREER_MATERIAL);
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

    /** schema 中所有引用 {@code resource} 的表，必须在 SQL 里出现过（作为 NOT EXISTS 的子查询）。 */
    private void assertCoverage(String resource, String sql) {
        Set<String> referencing = jdbc.queryForList(
                        "SELECT DISTINCT TABLE_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                                + "WHERE REFERENCED_TABLE_NAME = ?",
                        String.class, resource.toUpperCase())
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertFalse(referencing.isEmpty(),
                "schema 元数据里查不到任何引用 " + resource + " 的外键 —— 本用例的查询方式可能不适用于当前数据库，"
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
                        + "（SQL 中已出现：" + mentioned + "）");
    }
}
