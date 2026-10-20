package com.intelligentresume.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V36 存量回填的数据形状测试。
 *
 * <p>{@code V36__application_status_history.sql} 的
 * {@code INSERT ... SELECT ... COALESCE(stage_entered_at, created_at) FROM application_record}
 * 是审计表的历史起点：它决定了老用户打开投递详情时看到的第一条状态事件。全量表级门禁
 * （H2 全量 + MySQL 5.7）都在<b>空 application_record 表</b>上跑该迁移，只能证明语句可解析，
 * 无法证明「先有存量行、再回填」时写入的数据形状正确。本测试补上这段。
 *
 * <p>实现方式与 {@link FlywayMigrationIT} 的 V18/V19 用例一致：在独立内存库上先迁移到 V35，
 * 此时插入存量 {@code application_record} 行（模拟升级前用户既有的投递记录），再迁移到最新
 * 版本，让 V36 在真实数据上执行；随后直接断言历史行的形状。这样无需 {@code @Sql} 清表，
 * 也不会与已应用 Flyway 版本的共享测试库互相污染。
 */
class ApplicationStatusHistoryBackfillTest {

    private static final String URL_TAIL =
            ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE";

    /** V36 之前的最新版本：存量行必须在 V36 回填之前插入。 */
    private static final String TARGET_BEFORE_V36 = "35";

    /** fixture 1：stage_entered_at 非空，回填必须取它而不是 created_at。 */
    private static final LocalDateTime OFFERED_ENTERED_AT = LocalDateTime.of(2026, 1, 5, 10, 0, 0, 123_000_000);
    private static final LocalDateTime OFFERED_CREATED_AT = LocalDateTime.of(2026, 1, 2, 9, 0, 0);

    /** fixture 2：stage_entered_at 为 NULL（V26 列可空，旧写入路径未填），回填必须回退 created_at。 */
    private static final LocalDateTime APPLIED_CREATED_AT = LocalDateTime.of(2026, 2, 3, 8, 30, 0, 500_000_000);

    @Test
    @DisplayName("V36 回填：每条存量记录生成且仅生成 1 条 from_status 为 NULL 的历史行")
    void backfillCreatesExactlyOneNullFromRowPerLegacyRecord() throws Exception {
        String url = newDatabase();
        SeededRecords seeded = seedLegacyRecords(url);
        migrateToLatest(url);

        Map<Long, HistoryRow> history = readHistory(url);

        assertEquals(2, history.size(),
                "两条存量记录应各生成 1 条历史行（不能漏行，也不能重复插入）");
        assertTrue(history.containsKey(seeded.offeredId()), "OFFERED 存量记录缺少历史行");
        assertTrue(history.containsKey(seeded.appliedId()), "APPLIED 存量记录缺少历史行");
        for (HistoryRow row : history.values()) {
            assertNull(row.fromStatus(),
                    "回填无法得知迁移前状态，from_status 必须为 NULL，实际为: " + row.fromStatus());
        }
        // 兜底：不允许存在没有历史行的存量记录（漏行会让详情页时间线缺首条事件）。
        assertEquals(0L, countLegacyRecordsWithoutHistory(url), "存在未生成历史行的存量记录");

        // to_status 必须等于该记录当前的 status —— 用 join 在库侧复核，避免只校验硬编码常量。
        assertEquals(0L, countStatusMismatches(url),
                "历史行的 to_status 必须等于 application_record.status");
        assertEquals("OFFERED", history.get(seeded.offeredId()).toStatus());
        assertEquals("APPLIED", history.get(seeded.appliedId()).toStatus());
    }

    @Test
    @DisplayName("V36 回填：changed_at 优先取 stage_entered_at，为空时回退 created_at")
    void backfillResolvesChangedAtFromStageEnteredAtThenCreatedAt() throws Exception {
        String url = newDatabase();
        SeededRecords seeded = seedLegacyRecords(url);
        migrateToLatest(url);

        Map<Long, HistoryRow> history = readHistory(url);

        HistoryRow offered = history.get(seeded.offeredId());
        assertNotNull(offered, "OFFERED 存量记录缺少历史行");
        assertEquals(OFFERED_ENTERED_AT, offered.changedAt(),
                "stage_entered_at 非空时必须作为 changed_at");
        // 反向断言：若 COALESCE 参数写反，取值会落到 created_at 而上面一条就会误判为通过。
        assertFalse(OFFERED_CREATED_AT.equals(offered.changedAt()),
                "changed_at 不得退化为 created_at");

        HistoryRow applied = history.get(seeded.appliedId());
        assertNotNull(applied, "APPLIED 存量记录缺少历史行");
        assertEquals(APPLIED_CREATED_AT, applied.changedAt(),
                "stage_entered_at 为 NULL 时必须回退 created_at");

        // 审计列由 V36 的 DEFAULT CURRENT_TIMESTAMP(3) 补齐，不能为空。
        // application_record_id 的悬空由 V36 的外键 fk_application_status_history_record 兜住。
        assertEquals(0L, countRowsWithNullAuditColumns(url),
                "历史行的 created_at/updated_at 必须由默认值补齐");
    }

    // ---- 数据库准备 ----

    private String newDatabase() {
        return "jdbc:h2:mem:status_history_backfill_"
                + UUID.randomUUID().toString().replace("-", "") + URL_TAIL;
    }

    private void migrateToV35(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target(TARGET_BEFORE_V36).load().migrate();
    }

    private void migrateToLatest(String url) {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();
    }

    /**
     * 迁移到 V35 后写入两条存量投递记录（升级前用户已存在的数据），再迁移到最新版由 V36 回填。
     *
     * @return 两条存量记录的 id，供断言定位历史行
     */
    private SeededRecords seedLegacyRecords(String url) throws SQLException {
        migrateToV35(url);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user (username, email, password_hash, status, created_at, updated_at) "
                    + "VALUES ('v36_backfill', 'v36-backfill@example.test', 'hash', 'ACTIVE', NOW(), NOW())");
            long userId = generatedId(statement, "SELECT id FROM user WHERE username = 'v36_backfill'");

            statement.execute("INSERT INTO job_description (user_id, title, jd_text, created_at, updated_at) "
                    + "VALUES (" + userId + ", 'Backend Engineer', 'Java', NOW(), NOW())");
            long jobId = generatedId(statement, "SELECT id FROM job_description WHERE user_id = " + userId);

            statement.execute("INSERT INTO resume (user_id, title, created_at, updated_at) "
                    + "VALUES (" + userId + ", 'Backfill Resume', NOW(), NOW())");
            long resumeId = generatedId(statement, "SELECT id FROM resume WHERE user_id = " + userId);

            statement.execute("INSERT INTO resume_version (resume_id, version_no, source_type, resume_json, "
                    + "created_by, created_at, updated_at) "
                    + "VALUES (" + resumeId + ", 1, 'MANUAL', '{}', " + userId + ", NOW(), NOW())");
            long versionId = generatedId(statement,
                    "SELECT id FROM resume_version WHERE resume_id = " + resumeId);

            long offeredId = insertLegacyRecord(connection, userId, jobId, versionId,
                    "OFFERED", OFFERED_ENTERED_AT, OFFERED_CREATED_AT);
            long appliedId = insertLegacyRecord(connection, userId, jobId, versionId,
                    "APPLIED", null, APPLIED_CREATED_AT);
            return new SeededRecords(offeredId, appliedId);
        }
    }

    private long insertLegacyRecord(Connection connection, long userId, long jobId, long versionId,
                                    String status, LocalDateTime stageEnteredAt, LocalDateTime createdAt)
            throws SQLException {
        String sql = "INSERT INTO application_record (user_id, job_description_id, resume_version_id, status, "
                + "stage_entered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, userId);
            insert.setLong(2, jobId);
            insert.setLong(3, versionId);
            insert.setString(4, status);
            if (stageEnteredAt == null) {
                insert.setNull(5, java.sql.Types.TIMESTAMP);
            } else {
                insert.setTimestamp(5, Timestamp.valueOf(stageEnteredAt));
            }
            insert.setTimestamp(6, Timestamp.valueOf(createdAt));
            insert.setTimestamp(7, Timestamp.valueOf(createdAt));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next(), "插入存量 application_record 应返回自增主键");
                return keys.getLong(1);
            }
        }
    }

    // ---- 断言辅助 ----

    private Map<Long, HistoryRow> readHistory(String url) throws SQLException {
        Map<Long, HistoryRow> rows = new LinkedHashMap<>();
        // 不加 ORDER BY：该内存库连接串带 NON_KEYWORDS=ORDER（沿用 FlywayMigrationIT 的建库参数，
        // 让 user/key/value/order 等 MySQL 关键字可作标识符），ORDER BY 会被解析为标识符而报语法错误。
        // 断言以 application_record_id 为键，无需行序。
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT application_record_id, from_status, to_status, changed_at "
                             + "FROM application_status_history")) {
            while (result.next()) {
                long recordId = result.getLong("application_record_id");
                HistoryRow previous = rows.put(recordId, new HistoryRow(
                        result.getString("from_status"),
                        result.getString("to_status"),
                        result.getTimestamp("changed_at").toLocalDateTime()));
                assertNull(previous, "存量记录 " + recordId + " 被回填了多条历史行");
            }
        }
        return rows;
    }

    private long countLegacyRecordsWithoutHistory(String url) throws SQLException {
        return scalar(url, "SELECT COUNT(*) FROM application_record a "
                + "WHERE NOT EXISTS (SELECT 1 FROM application_status_history h "
                + "WHERE h.application_record_id = a.id)");
    }

    private long countStatusMismatches(String url) throws SQLException {
        return scalar(url, "SELECT COUNT(*) FROM application_status_history h "
                + "JOIN application_record a ON a.id = h.application_record_id "
                + "WHERE h.to_status <> a.status");
    }

    private long countRowsWithNullAuditColumns(String url) throws SQLException {
        return scalar(url, "SELECT COUNT(*) FROM application_status_history "
                + "WHERE created_at IS NULL OR updated_at IS NULL");
    }

    private long scalar(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "计数查询应返回一行: " + sql);
            return result.getLong(1);
        }
    }

    private long generatedId(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "查询应返回至少一行: " + sql);
            return result.getLong(1);
        }
    }

    private record SeededRecords(long offeredId, long appliedId) {
    }

    private record HistoryRow(String fromStatus, String toStatus, LocalDateTime changedAt) {
    }
}
