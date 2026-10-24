package com.intelligentresume.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产口径的持久化门禁：在真实 MySQL 8.4 上从零执行全部迁移（V1 → V37）。
 *
 * <p>CI：`ci.yml` 的 `migration-84` job 会起 mysql:8.4 并跑本类，随后用
 * `scripts/assert-tests-ran.py` 断言它**真的执行**（被跳过不算通过）。
 *
 * <p>为什么需要它：仓库里 39 个 {@code *IT} 跑 H2 的 MySQL 兼容模式，
 * {@code functional.yml} 起的也是 {@code mysql:8.0}（落后于生产的 8.4），
 * 因此 <b>V1~V18 从未在真实 MySQL 上执行过</b> —— 而 V18 恰好用了 8.0+ 的窗口函数。
 * 这条用例补的正是这个空白。
 *
 * <p>与 {@link MySql57MigrationLiveIT} 的分工：
 * <ul>
 *   <li>本类 = <b>全新安装</b>（V1→V37，37 条），对齐生产版本 8.4；</li>
 *   <li>5.7 类 = <b>存量升级</b>（V19 基线→V37，18 条），只证明老库能升上来，
 *       不宣称「5.7 可以全新安装」（V18 的窗口函数在 5.7 上不存在）。</li>
 * </ul>
 *
 * <p>门控：{@code MYSQL84_LIVE_TEST=true} 才执行。未设时整类跳过，由调用方
 * （{@code scripts/persistence-gate.remote.sh}）解析 surefire 报告确认「真的跑了」，
 * 而不是把「跳过」当「通过」。
 *
 * <p>⚠ 新增 Flyway 迁移时必须同步改本类的 {@code 36}；{@code MySql57BaselineContractTest}
 * 也会静态对账迁移条数，两处都不要漏。
 */
@EnabledIfEnvironmentVariable(named = "MYSQL84_LIVE_TEST", matches = "true")
class MySql84FreshMigrationIT {

    /** 迁移总数（V1~V37）。新增迁移时同步改这里。 */
    private static final int EXPECTED_MIGRATIONS = 37;

    /** 最新版本号（V37）。新增迁移时同步改这里。 */
    private static final String EXPECTED_LATEST_VERSION = "37";

    @Test
    void freshInstallRunsEveryMigrationOnMySql84() throws Exception {
        String schemaUrl = requiredEnvironment("MYSQL84_JDBC_URL");
        String user = requiredEnvironment("MYSQL84_USER");
        String password = requiredEnvironment("MYSQL84_PASSWORD");

        try (Connection connection = DriverManager.getConnection(schemaUrl, user, password);
             Statement statement = connection.createStatement()) {

            // ① 必须真的是生产口径的 8.4：跑错版本却记绿，等于门禁白设
            String version = scalar(statement, "SELECT VERSION()");
            assertTrue(version.startsWith("8.4."),
                    "Expected MySQL 8.4 (production baseline) but found " + version
                            + " —— 若生产升级到新的 8.x，请同步更新本断言与 docs/04");

            // ② 库名护栏：防止误连生产库（intelligent_resume）把迁移写进去
            String schema = scalar(statement, "SELECT DATABASE()");
            assertTrue(schema.startsWith("intelligent_resume_gate84_"),
                    "Refusing to migrate a non-gate schema: " + schema);

            // ③ 字符集：V23/V24 建表不写 CHARSET，继承库默认；库若不合法则中文种子会失真
            assertEquals("utf8mb4", scalar(statement, "SELECT @@character_set_database"));
            assertEquals("utf8mb4_unicode_ci", scalar(statement, "SELECT @@collation_database"));

            // ④ 从零迁移：不设 baseline（对比 5.7 门禁的 baselineVersion("19")）
            Flyway flyway = Flyway.configure()
                    .dataSource(schemaUrl, user, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .load();

            assertEquals(EXPECTED_MIGRATIONS, flyway.migrate().migrationsExecuted,
                    "全部 " + EXPECTED_MIGRATIONS + " 条迁移都应在 MySQL 8.4 上成功执行");
            assertEquals(EXPECTED_LATEST_VERSION, scalar(statement,
                            "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1"),
                    "迁移链应停在最新版本");
            assertEquals("0", scalar(statement,
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"),
                    "不应有失败的迁移记录");

            // ⑤ 关键表落地（抽样覆盖不同阶段：V1 早期表、V20+ 面试、V35 删号、V36 状态历史）
            assertEquals("4", scalar(statement,
                            "SELECT COUNT(*) FROM information_schema.tables"
                                    + " WHERE table_schema = DATABASE()"
                                    + " AND table_name IN ('resume', 'interview_ai_attempt',"
                                    + " 'account_deletion_job', 'application_status_history')"),
                    "关键表缺失说明迁移链在中间某步中断");

            // ⑥ V36 的结构细节：组合索引两列 + 外键级联删除
            assertEquals("2", scalar(statement,
                            "SELECT COUNT(*) FROM information_schema.statistics"
                                    + " WHERE table_schema = DATABASE()"
                                    + " AND table_name = 'application_status_history'"
                                    + " AND index_name = 'idx_application_status_history_application_changed'"),
                    "V36 的组合索引应覆盖 (application_record_id, changed_at) 两列");
            assertEquals("CASCADE", scalar(statement,
                            "SELECT delete_rule FROM information_schema.referential_constraints"
                                    + " WHERE constraint_schema = DATABASE()"
                                    + " AND constraint_name = 'fk_application_status_history_record'"),
                    "V36 外键应为 ON DELETE CASCADE（删投递记录时同步清历史）");
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required for the MySQL 8.4 live gate");
        return value;
    }

    private String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "SQL returned no rows: " + sql);
            return resultSet.getString(1);
        }
    }
}
