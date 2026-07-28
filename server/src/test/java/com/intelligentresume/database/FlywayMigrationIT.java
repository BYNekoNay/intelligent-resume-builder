package com.intelligentresume.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Flyway 迁移集成测试。
 *
 * <p>使用 H2 内存数据库（MySQL 兼容模式）验证 V1__m1_m2_init 迁移的正确性，
 * 包括表结构、唯一约束、外键约束以及迁移失败时阻止应用启动的行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class FlywayMigrationIT {

    /** V1 迁移应创建的 11 张表（大写，H2 默认存储为大写）。 */
    private static final Set<String> EXPECTED_TABLES = new HashSet<>(Arrays.asList(
            "USER", "AUTH_SESSION", "CAREER_MATERIAL", "RESUME", "RESUME_VERSION",
            "JOB_DESCRIPTION", "RESUME_MATERIAL_REFERENCE", "MATCH_RESULT",
            "AI_CONSENT", "AI_TASK", "EXPORT_TASK"
    ));

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V1__m1_m2_init 应用成功")
    void v1_applied() {
        MigrationInfo v1 = Arrays.stream(flyway.info().applied())
                .filter(m -> "1".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v1, "V1 迁移记录应存在");
        // Flyway 10.x getDisplayName() 返回 "Success"（首字母大写），使用 equalsIgnoreCase
        assertTrue("SUCCESS".equalsIgnoreCase(v1.getState().getDisplayName()),
                "V1 迁移状态应为 SUCCESS，实际为: " + v1.getState().getDisplayName());
    }

    @Test
    @DisplayName("11 张表均已创建")
    void eleven_tables_exist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> found = new HashSet<>();
            try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    found.add(rs.getString("TABLE_NAME"));
                }
            }
            for (String table : EXPECTED_TABLES) {
                assertTrue(found.contains(table), "表 " + table + " 应已创建");
            }
        }
    }

    @Test
    @DisplayName("重复启动不重复建表(已应用版本不重复执行)")
    void restart_no_re_migration() {
        // 再次调用 migrate()，已应用的版本不会重复执行
        int appliedCount = flyway.migrate().migrationsExecuted;
        assertEquals(0, appliedCount, "重复执行 migrate() 不应有新迁移");

        // V1 仍然只有一条 SUCCESS 记录
        long v1Count = Arrays.stream(flyway.info().applied())
                .filter(m -> "1".equals(m.getVersion().getVersion()))
                .count();
        assertEquals(1, v1Count, "V1 迁移记录应只有一条");
    }

    @Test
    @DisplayName("ai_task 唯一约束 uk_ai_task_idem 存在")
    void ai_task_idempotency_unique_constraint_exists() throws Exception {
        // 通过插入重复数据验证唯一约束 (user_id, task_type, idempotency_key) 存在
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 清理可能的残留测试数据（按 FK 依赖顺序）
            stmt.execute("DELETE FROM ai_task WHERE idempotency_key = '__uk_idem_test__'");
            stmt.execute("DELETE FROM user WHERE username = '__uk_idem_test_user__'");

            // 创建 FK 依赖：ai_task.user_id → user.id
            stmt.execute("INSERT INTO user (username, email, password_hash, status, "
                    + "created_at, updated_at) "
                    + "VALUES ('__uk_idem_test_user__', '__uk_idem_test@test.com', "
                    + "'hash', 'ACTIVE', NOW(), NOW())");
            long userId = getGeneratedId(stmt,
                    "SELECT id FROM user WHERE username = '__uk_idem_test_user__'");

            // 插入第一条记录
            stmt.execute("INSERT INTO ai_task (user_id, task_type, idempotency_key, "
                    + "request_fingerprint, input_snapshot_json, status, retry_count, "
                    + "created_at, updated_at) "
                    + "VALUES (" + userId + ", 'TEST_TYPE', '__uk_idem_test__', 'fp1', '{}', "
                    + "'PENDING', 0, NOW(), NOW())");

            // 插入相同 (user_id, task_type, idempotency_key) 应触发唯一约束违反
            try {
                stmt.execute("INSERT INTO ai_task (user_id, task_type, idempotency_key, "
                        + "request_fingerprint, input_snapshot_json, status, retry_count, "
                        + "created_at, updated_at) "
                        + "VALUES (" + userId + ", 'TEST_TYPE', '__uk_idem_test__', 'fp2', '{}', "
                        + "'PENDING', 0, NOW(), NOW())");
                fail("插入重复的 (user_id, task_type, idempotency_key) 应触发唯一约束违反");
            } catch (SQLException e) {
                // 预期：唯一约束违反（H2 SQLSTATE 23505）
            }

            // 清理测试数据（按 FK 依赖顺序）
            stmt.execute("DELETE FROM ai_task WHERE idempotency_key = '__uk_idem_test__'");
            stmt.execute("DELETE FROM user WHERE id = " + userId);
        }
    }

    @Test
    @DisplayName("ai_task 外键约束 fk_ai_task_result_version 存在")
    void ai_task_result_version_fk_exists() throws Exception {
        // 注：T01 手册原测试名为 uk_ai_task_result_version（唯一约束），
        // 但 V1 DDL 实际定义的是外键约束 fk_ai_task_result_version，此处按实际 schema 验证。
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean fkFound = false;
            try (ResultSet rs = meta.getImportedKeys(null, "PUBLIC", "AI_TASK")) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    String pkTable = rs.getString("PKTABLE_NAME");
                    String fkColumn = rs.getString("FKCOLUMN_NAME");
                    if ("FK_AI_TASK_RESULT_VERSION".equalsIgnoreCase(fkName)
                            && "RESUME_VERSION".equalsIgnoreCase(pkTable)
                            && "RESULT_RESUME_VERSION_ID".equalsIgnoreCase(fkColumn)) {
                        fkFound = true;
                        break;
                    }
                }
            }
            assertTrue(fkFound,
                    "ai_task 表应存在外键 fk_ai_task_result_version 引用 resume_version(id)");
        }
    }

    @Test
    @DisplayName("resume_version 唯一约束 uk_resume_version_no 存在")
    void resume_version_unique_constraint_exists() throws Exception {
        // 通过插入重复数据验证唯一约束 (resume_id, version_no) 存在
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 清理可能的残留测试数据（按 FK 依赖顺序：子表先删）
            stmt.execute("DELETE FROM resume_version WHERE resume_id IN "
                    + "(SELECT id FROM resume WHERE title = '__uk_rv_test_resume__')");
            stmt.execute("DELETE FROM resume WHERE title = '__uk_rv_test_resume__'");
            stmt.execute("DELETE FROM user WHERE username = '__uk_rv_test_user__'");

            // 创建 FK 依赖链：user → resume → resume_version
            stmt.execute("INSERT INTO user (username, email, password_hash, status, "
                    + "created_at, updated_at) "
                    + "VALUES ('__uk_rv_test_user__', '__uk_rv_test@test.com', "
                    + "'hash', 'ACTIVE', NOW(), NOW())");
            long userId = getGeneratedId(stmt,
                    "SELECT id FROM user WHERE username = '__uk_rv_test_user__'");

            stmt.execute("INSERT INTO resume (user_id, title, created_at, updated_at) "
                    + "VALUES (" + userId + ", '__uk_rv_test_resume__', NOW(), NOW())");
            long resumeId = getGeneratedId(stmt,
                    "SELECT id FROM resume WHERE title = '__uk_rv_test_resume__'");

            stmt.execute("INSERT INTO resume_version (resume_id, version_no, source_type, "
                    + "resume_json, created_by, created_at, updated_at) "
                    + "VALUES (" + resumeId + ", 1, 'MANUAL', '{}', "
                    + userId + ", NOW(), NOW())");

            // 插入相同 (resume_id, version_no) 应触发唯一约束违反
            try {
                stmt.execute("INSERT INTO resume_version (resume_id, version_no, source_type, "
                        + "resume_json, created_by, created_at, updated_at) "
                        + "VALUES (" + resumeId + ", 1, 'MANUAL', '{}', "
                        + userId + ", NOW(), NOW())");
                fail("插入重复的 (resume_id, version_no) 应触发唯一约束违反");
            } catch (SQLException e) {
                // 预期：唯一约束违反
            }

            // 清理测试数据（按 FK 依赖顺序）
            stmt.execute("DELETE FROM resume_version WHERE resume_id = " + resumeId);
            stmt.execute("DELETE FROM resume WHERE id = " + resumeId);
            stmt.execute("DELETE FROM user WHERE id = " + userId);
        }
    }

    @Test
    @DisplayName("迁移失败阻止应用启动")
    void migration_failure_blocks_startup() {
        // 直接使用 Flyway API 验证：包含错误 SQL 的迁移文件会导致 Flyway 抛出异常，
        // 从而阻止 Spring 应用上下文启动（Flyway 在 Bean 初始化阶段执行迁移）。
        Flyway badFlyway = Flyway.configure()
                .dataSource(
                        "jdbc:h2:mem:bad_migration_direct;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                                + "NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE",
                        "sa", "")
                .locations("classpath:db/bad-migration")
                .baselineOnMigrate(false)
                .load();
        assertThrows(Exception.class, badFlyway::migrate,
                "包含引用不存在表的迁移文件应导致 Flyway 迁移失败");
    }

    @Test
    @DisplayName("V18 should backfill historical interview rounds and enforce uniqueness")
    void v18_backfills_historical_interview_rounds() throws Exception {
        String databaseName = "interview_round_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("17").load().migrate();

        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user (username, email, password_hash, status, created_at, updated_at) "
                    + "VALUES ('round_upgrade', 'round-upgrade@example.test', 'hash', 'ACTIVE', NOW(), NOW())");
            long userId = getGeneratedId(statement, "SELECT id FROM user WHERE username = 'round_upgrade'");
            statement.execute("INSERT INTO job_description (user_id, title, jd_text, created_at, updated_at) "
                    + "VALUES (" + userId + ", 'Engineer', 'Java', NOW(), NOW())");
            long jobId = getGeneratedId(statement, "SELECT id FROM job_description WHERE user_id = " + userId);
            statement.execute("INSERT INTO interview_session (user_id, source_type, external_resume_text, "
                    + "job_description_id, interview_mode, status, current_question, created_at, updated_at) "
                    + "VALUES (" + userId + ", 'EXTERNAL_RESUME', 'Resume', " + jobId
                    + ", 'TECHNICAL', 'IN_PROGRESS', 'Question', NOW(), NOW())");
            long sessionId = getGeneratedId(statement, "SELECT id FROM interview_session WHERE user_id = " + userId);
            statement.execute("INSERT INTO interview_record (session_id, question_text, answer_text, round_score, "
                    + "feedback_json, created_at, updated_at) VALUES (" + sessionId
                    + ", 'Q1', 'A1', 60, '{}', TIMESTAMP '2026-01-01 00:00:00', NOW())");
            statement.execute("INSERT INTO interview_record (session_id, question_text, answer_text, round_score, "
                    + "feedback_json, created_at, updated_at) VALUES (" + sessionId
                    + ", 'Q2', 'A2', 70, '{}', TIMESTAMP '2026-01-01 00:00:00', NOW())");
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertRound(statement, "Q1", 1);
            assertRound(statement, "Q2", 2);
            try (ResultSet column = statement.executeQuery(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_name = 'INTERVIEW_RECORD' AND column_name = 'ROUND_NO'")) {
                assertTrue(column.next());
                assertEquals("NO", column.getString(1));
            }
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE interview_record SET round_no = 1 WHERE id = "
                            + "(SELECT MAX(id) FROM interview_record)"));
        }
    }

    private void assertRound(Statement statement, String question, int expectedRound) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT round_no FROM interview_record WHERE question_text = '" + question + "'")) {
            assertTrue(result.next());
            assertEquals(expectedRound, result.getInt(1));
            assertFalse(result.next());
        }
    }

    // ---- 辅助方法 ----

    /**
     * 执行查询并返回第一行第一列的 long 值。
     */
    private long getGeneratedId(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回至少一行: " + sql);
            return rs.getLong(1);
        }
    }
}
