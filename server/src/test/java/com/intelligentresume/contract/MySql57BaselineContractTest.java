package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 5.7 升级门禁的**基线自洽性**门禁（静态）。第六十九批·续补。
 *
 * <p>背景：`MySql57MigrationLiveIT` 是**手动门**（`@EnabledIfEnvironmentVariable(MYSQL57_LIVE_TEST)`），
 * 它从 `server/src/test/resources/mysql57/v19-schema.sql` 这个**基线快照**起步，再让 Flyway 从 19
 * 跑到最新。它自身的断言都校验「V20 之后」的效果 —— 于是有一个**看不见的前提**：
 *
 * <blockquote>基线快照必须与 V1~V19 迁移建出的结构一致。</blockquote>
 *
 * <p>如果哪天有人改了 V1~V19 里的迁移（或快照被手改漏了），这条前提就被破坏，而门禁**只会更少地证明
 * 东西**，不会报错 —— 又一处「永远通过」的形态。第六十九批我**手工**核对过一次（比对 V1~V19 文件
 * 是否被改过 + 表集合是否一致，结果一致），本类把那次核对固化成机器判据。
 *
 * <p>另外顺手挡住三个「改了 A 忘了改 B」的漂移（它们此前只会在**手动门跑起来时**才暴露）：
 * <ol>
 *   <li>快照文件名里的版本 vs `baselineVersion(...)`；</li>
 *   <li>`baselineVersion` vs 快照里实际建的表所对应的迁移范围；</li>
 *   <li>手动门里**硬编码的两个数字**（`migrationsExecuted` 期望值、`MAX(version)`）vs 实际的迁移文件数
 *       —— 新增一条迁移就会让手动门红，若无人手动跑就一直是「未知」。</li>
 * </ol>
 */
class MySql57BaselineContractTest {

    private static final String MIGRATIONS_DIR = "server/src/main/resources/db/migration";
    private static final String BASELINE_SNAPSHOT = "server/src/test/resources/mysql57/v19-schema.sql";
    private static final String LIVE_GATE =
            "server/src/test/java/com/intelligentresume/database/MySql57MigrationLiveIT.java";

    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__([\\w-]+)\\.sql");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z_][a-z0-9_]*)`?");
    private static final Pattern SNAPSHOT_VERSION = Pattern.compile("v(\\d+)-schema\\.sql");
    private static final Pattern BASELINE_VERSION = Pattern.compile("baselineVersion\\(\"(\\d+)\"\\)");
    private static final Pattern EXPECTED_MIGRATIONS = Pattern.compile(
            "assertEquals\\((\\d+),\\s*flyway\\.migrate\\(\\)\\.migrationsExecuted\\)");
    private static final Pattern EXPECTED_MAX_VERSION = Pattern.compile(
            "assertEquals\\(\"(\\d+)\",\\s*scalar\\(statement,\\s*\"SELECT MAX\\(");

    @Test
    @DisplayName("基线快照建的表集合 == 版本 ≤ 基线的迁移建的表集合（否则门禁证明的范围是错的）")
    void baselineSnapshotMatchesMigrationsUpToItsVersion() throws Exception {
        int baseline = baselineVersion();
        Set<String> fromMigrations = tablesCreatedUpTo(baseline);
        Set<String> fromSnapshot = tablesInSnapshot();

        assertTrue(fromMigrations.size() >= 15,
                "从 V1~V" + baseline + " 解析到的表过少（" + fromMigrations.size() + "），解析可能已失效");
        assertTrue(fromSnapshot.size() >= 15,
                "从基线快照解析到的表过少（" + fromSnapshot.size() + "），解析可能已失效");

        Set<String> onlyInMigrations = diff(fromMigrations, fromSnapshot);
        Set<String> onlyInSnapshot = diff(fromSnapshot, fromMigrations);
        assertEquals(Set.of(), onlyInMigrations,
                "V1~V" + baseline + " 建了这些表，但基线快照里没有：" + onlyInMigrations
                        + " —— 快照已与迁移脱节，手动门证明的范围不再成立");
        assertEquals(Set.of(), onlyInSnapshot,
                "基线快照里有这些表，但 V1~V" + baseline + " 并不创建它们：" + onlyInSnapshot
                        + " —— 快照来源不明，或迁移被改动过");
    }

    @Test
    @DisplayName("快照文件名版本 / baselineVersion / 迁移范围三者一致")
    void baselineVersionIsDeclaredConsistently() throws Exception {
        Matcher file = SNAPSHOT_VERSION.matcher(Path.of(BASELINE_SNAPSHOT).getFileName().toString());
        assertTrue(file.find(), "基线快照文件名应形如 v<N>-schema.sql：" + BASELINE_SNAPSHOT);
        int fromFileName = Integer.parseInt(file.group(1));
        int fromGate = baselineVersion();

        assertEquals(fromFileName, fromGate,
                "快照文件名声明 v" + fromFileName + "，而手动门 baselineVersion(" + fromGate
                        + ") —— 二者不一致会把「升级起点」悄悄挪到别处");

        Set<Integer> versions = migrationVersions();
        assertTrue(versions.contains(fromGate),
                "基线版本 V" + fromGate + " 在迁移目录里不存在（已有：" + versions + "）");
        assertTrue(versions.stream().max(Integer::compareTo).orElse(0) > fromGate,
                "基线版本必须小于最新迁移版本，否则手动门没有可升级的内容");
    }

    @Test
    @DisplayName("手动门里硬编码的迁移数/最大版本 == 实际迁移（新增迁移后忘了更新即报红）")
    void liveGateHardcodedNumbersMatchRepository() throws Exception {
        String gate = SourceText.read(repoFile(LIVE_GATE));
        Set<Integer> versions = migrationVersions();
        int max = versions.stream().max(Integer::compareTo).orElseThrow();
        // 手动门期望的「升级条数」= 总迁移 − 基线及以下（基线先取出，避免在 lambda 里调用 throws 方法）
        int baseline = baselineVersion();
        int expectedMigrations = versions.size()
                - (int) versions.stream().filter(v -> v <= baseline).count();

        Matcher migrations = EXPECTED_MIGRATIONS.matcher(gate);
        assertTrue(migrations.find(), "未能在手动门里解析出 migrationsExecuted 的期望值，门禁可能已失效");
        assertEquals(expectedMigrations, Integer.parseInt(migrations.group(1)),
                "手动门期望升级 " + migrations.group(1) + " 条迁移，而实际（总迁移 − 基线）是 "
                        + expectedMigrations + " 条 —— 新增或删除迁移后必须同步这里，否则手动门一跑就红"
                        + "（而它平时不跑，问题会被拖到很久以后）");

        Matcher maxVersion = EXPECTED_MAX_VERSION.matcher(gate);
        assertTrue(maxVersion.find(), "未能在手动门里解析出 MAX(version) 的期望值，门禁可能已失效");
        assertEquals(max, Integer.parseInt(maxVersion.group(1)),
                "手动门期望 MAX(version)=" + maxVersion.group(1) + "，而实际是 " + max);
    }

    // ---------- 解析 ----------

    private int baselineVersion() throws Exception {
        String gate = SourceText.read(repoFile(LIVE_GATE));
        Matcher matcher = BASELINE_VERSION.matcher(gate);
        assertTrue(matcher.find(), "未能在手动门里解析出 baselineVersion，门禁可能已失效");
        return Integer.parseInt(matcher.group(1));
    }

    private Set<Integer> migrationVersions() throws Exception {
        Set<Integer> versions = new TreeSet<>();
        try (Stream<Path> files = Files.list(repoFile(MIGRATIONS_DIR))) {
            for (Path file : files.toList()) {
                Matcher matcher = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        assertTrue(!versions.isEmpty(), "迁移目录下未解析到任何 V*.sql：" + MIGRATIONS_DIR);
        return versions;
    }

    private Set<String> tablesCreatedUpTo(int upTo) throws Exception {
        Set<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(repoFile(MIGRATIONS_DIR))) {
            for (Path file : files.toList()) {
                Matcher name = MIGRATION_FILE.matcher(file.getFileName().toString());
                if (!name.matches() || Integer.parseInt(name.group(1)) > upTo) {
                    continue;
                }
                tables.addAll(createTables(SourceText.read(file)));
            }
        }
        return tables;
    }

    private Set<String> tablesInSnapshot() throws Exception {
        return createTables(SourceText.read(repoFile(BASELINE_SNAPSHOT)));
    }

    private Set<String> createTables(String sql) {
        Set<String> tables = new TreeSet<>();
        for (String line : sql.split("\n")) {
            Matcher matcher = CREATE_TABLE.matcher(line);
            if (matcher.find()) {
                tables.add(matcher.group(1).toLowerCase());
            }
        }
        return tables;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> only = new LinkedHashSet<>(a);
        only.removeAll(b);
        return only;
    }

    private static Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        return Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
    }
}
