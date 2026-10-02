package com.intelligentresume.retention;

import com.intelligentresume.contract.SourceText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账户级联删除清单的**覆盖完整性**门禁（静态，第 74 批）。
 *
 * <p>漏一张表 = 该表数据在账户硬删后残留 = PII 清理承诺落空且**无任何报错**——
 * 与 RetentionPurgeRepositorySchemaTest 同源的「引用/覆盖清单漂移」防线，但方向相反：
 * 分档清扫防「误删被引用者」，账户清扫防「漏删该删者」。判据取自迁移的机器可读反查：
 *
 * <ol>
 *   <li>全部迁移中**含 user_id 列**的表（18 张）必须各有直接 {@code DELETE … WHERE user_id = ?}；</li>
 *   <li>4 张无 user_id 的关联表（resume_version / resume_material_reference / match_result /
 *       interview_record）必须以 {@code IN (SELECT …)} 子查询形态覆盖；</li>
 *   <li>{@code communication_template} 含系统种子行 —— 其 DELETE **必须**带 user_id 条件
 *       （不带即全表删种子 = 灾难）；</li>
 *   <li>每张业务表恰好出现一次（重复 = 复制粘贴事故，遗漏 = 残留）。</li>
 * </ol>
 */
class AccountPurgeRepositorySchemaTest {

    private static final String MIGRATIONS_DIR = "server/src/main/resources/db/migration";
    // MULTILINE 不可省：^ 在无该标志时只匹配文本开头，逐文件解析会漏掉首个之外的全部建表
    // （第 74 批实测：首版缺 MULTILINE 时反查只找到 7/18 张表）。
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z_][a-z0-9_]*)`?");

    @Test
    @DisplayName("全部含 user_id 的表都被直接 DELETE 覆盖，无 user_id 的关联表都被 IN 子查询覆盖")
    void cascadeListCoversEveryUserOwnedTable() throws Exception {
        Set<String> withUserId = new TreeSet<>(tablesWithUserIdColumn());
        withUserId.remove("account_deletion_job"); // 任务表自身不级联（成功行留审计）

        String joined = String.join("\n", AccountPurgeRepository.PURGE_STATEMENTS);

        Set<String> missing = new TreeSet<>();
        Set<String> coveredDirect = new TreeSet<>();
        for (String table : withUserId) {
            Pattern direct = Pattern.compile("(?i)DELETE\\s+FROM\\s+" + table + "\\s+WHERE\\s+user_id\\s*=\\s*\\?");
            if (direct.matcher(joined).find()) coveredDirect.add(table);
            else missing.add(table);
        }
        assertTrue(missing.isEmpty(),
                "以下含 user_id 的表没有直接 DELETE 覆盖 —— 账户硬删后这些表的数据会残留：" + missing);

        Set<String> coveredViaSubquery = new TreeSet<>();
        for (String table : List.of("resume_version", "resume_material_reference", "match_result", "interview_record")) {
            Pattern via = Pattern.compile("(?i)DELETE\\s+FROM\\s+" + table + "\\s+WHERE\\s+.*IN\\s*\\(",
                    Pattern.DOTALL);
            if (via.matcher(joined).find()) coveredViaSubquery.add(table);
            else missing.add(table);
        }
        assertTrue(missing.isEmpty(),
                "以下无 user_id 的关联表缺少 IN 子查询形态的 DELETE：" + missing
                        + "（清单来源：docs/plans/2026-10-01-001 §7 与迁移外键链）");
        assertEquals(18, coveredDirect.size(), "直接覆盖的 user_id 表数应与迁移反查一致");
        assertEquals(4, coveredViaSubquery.size(), "经链覆盖的关联表数应为 4");
    }

    @Test
    @DisplayName("communication_template 的 DELETE 必须带 user_id 条件（系统种子不得被全表删除）")
    void systemSeedTemplatesSurviveAccountPurge() {
        String joined = String.join("\n", AccountPurgeRepository.PURGE_STATEMENTS);
        assertTrue(joined.contains("DELETE FROM communication_template WHERE user_id = ?"),
                "communication_template 含 is_system=1 的共享种子行（V23 共 14 行）——账户清扫只允许按 user_id "
                        + "删除用户自建模板，不带条件的 DELETE 会把全部用户的种子一起删掉");
    }

    @Test
    @DisplayName("每张业务表恰好出现一次（重复 = 复制粘贴事故）且 user 行由 deleteUser 单独删除")
    void everyTableAppearsExactlyOnce() throws Exception {
        Set<String> allTables = allCreatedTables();
        allTables.remove("user");
        allTables.remove("account_deletion_job");

        List<String> names = new ArrayList<>();
        for (String sql : AccountPurgeRepository.PURGE_STATEMENTS) {
            Matcher m = Pattern.compile("(?i)DELETE\\s+FROM\\s+([a-z_]+)").matcher(sql);
            assertTrue(m.find(), "语句无法解析出目标表：" + sql);
            names.add(m.group(1));
        }
        Set<String> distinct = new LinkedHashSet<>(names);
        assertEquals(allTables, distinct,
                "DELETE 覆盖集合与迁移建表集合不一致（多出 = 幽灵表；缺少 = 数据残留）");
        assertEquals(names.size(), distinct.size(), "存在重复的 DELETE 语句（复制粘贴事故）");
    }

    // ---------- 解析（与 MySql57BaselineContractTest 同源的迁移反查） ----------

    private Set<String> tablesWithUserIdColumn() throws IOException {
        Set<String> result = new TreeSet<>();
        try (Stream<Path> files = Files.list(repoFile(MIGRATIONS_DIR))) {
            for (Path file : files.toList()) {
                String sql = SourceText.read(file);
                // 逐表切块（CREATE TABLE 到下一个 CREATE TABLE），块内出现 user_id 列即计入
                List<Integer> starts = new ArrayList<>();
                Matcher sm = CREATE_TABLE.matcher(sql);
                while (sm.find()) starts.add(sm.start());
                for (int i = 0; i < starts.size(); i++) {
                    int begin = starts.get(i);
                    int end = i + 1 < starts.size() ? starts.get(i + 1) : sql.length();
                    String body = sql.substring(begin, end);
                    Matcher name = CREATE_TABLE.matcher(body);
                    if (!name.find()) continue;
                    if (body.toLowerCase().contains("user_id")) result.add(name.group(1));
                }
            }
        }
        return result;
    }

    private Set<String> allCreatedTables() throws IOException {
        Set<String> result = new TreeSet<>();
        try (Stream<Path> files = Files.list(repoFile(MIGRATIONS_DIR))) {
            for (Path file : files.toList()) {
                String sql = SourceText.read(file);
                Matcher m = CREATE_TABLE.matcher(sql);
                while (m.find()) result.add(m.group(1));
            }
        }
        return result;
    }

    private static Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        return Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
    }
}
