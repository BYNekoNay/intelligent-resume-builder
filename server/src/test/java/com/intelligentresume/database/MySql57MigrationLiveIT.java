package com.intelligentresume.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MYSQL57_LIVE_TEST", matches = "true")
class MySql57MigrationLiveIT {

    @Test
    void upgradesSupportedV19BaselineToCurrentOnMySql57() throws Exception {
        String schemaUrl = requiredEnvironment("MYSQL57_JDBC_URL");
        String user = requiredEnvironment("MYSQL57_USER");
        String password = requiredEnvironment("MYSQL57_PASSWORD");

        try (Connection connection = DriverManager.getConnection(schemaUrl, user, password);
             Statement statement = connection.createStatement()) {
            String version = scalar(statement, "SELECT VERSION()");
            assertTrue(version.startsWith("5.7."), "Expected MySQL 5.7 but found " + version);
            String schema = scalar(statement, "SELECT DATABASE()");
            assertTrue(schema.startsWith("intelligent_resume_gate_"));

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("mysql57/v19-schema.sql"));

            Flyway flyway = Flyway.configure()
                    .dataSource(schemaUrl, user, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("19")
                    .baselineDescription("MySQL 5.7 supported upgrade baseline")
                    .cleanDisabled(true)
                    .load();

            // V20~V33 共 14 条迁移必须全部在 MySQL 5.7 上成功（V23~V33 的 5.7 兼容由本门禁证明）
            assertEquals(14, flyway.migrate().migrationsExecuted);
            assertEquals("33", scalar(statement,
                    "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1"));
            assertEquals("1", scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='19' AND type='BASELINE' AND success=1"));

            // V20~V22（原有断言保持）
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "interview_session", "job_description_id")));
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "interview_session", "current_question")));
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "interview_session", "output_language")));
            assertEquals("ZH_CN", scalar(statement, columnDefaultSql(schema, "interview_session", "output_language")));
            assertEquals("1", scalar(statement, tableCountSql(schema, "interview_ai_attempt")));
            assertEquals("2", scalar(statement, uniqueAttemptIndexCountSql(schema)));

            // V23/V24：沟通模板表 + 内置模板种子（16 行，含 EN 变体；验证 5.7 上的中文/多行 INSERT）
            assertEquals("1", scalar(statement, tableCountSql(schema, "communication_template")));
            assertEquals("16", scalar(statement,
                    "SELECT COUNT(*) FROM communication_template WHERE is_system = 1"));
            assertEquals("1", scalar(statement, tableCountSql(schema, "interview_asset_section")));

            // V25：资产章节关联允许只挂素材（section_key 可空）
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "interview_asset_section", "section_key")));

            // V26/V27/V29/V30：新增可空列与乐观锁版本列（NOT NULL DEFAULT 0）
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "application_record", "next_follow_up_at")));
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "application_record", "stage_entered_at")));
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "career_material", "version")));
            assertEquals("0", scalar(statement, columnDefaultSql(schema, "career_material", "version")));
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "communication_template", "version")));
            assertEquals("0", scalar(statement, columnDefaultSql(schema, "communication_template", "version")));
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "resume", "version")));
            assertEquals("0", scalar(statement, columnDefaultSql(schema, "resume", "version")));

            // V28：面试资产 (user_id, interview_record_id) 唯一索引兜底幂等创建
            assertEquals("0", scalar(statement,
                    "SELECT non_unique FROM information_schema.statistics WHERE table_schema='" + schema
                            + "' AND table_name='interview_answer_asset'"
                            + " AND index_name='uq_interview_asset_user_record' AND seq_in_index=1"));

            // V31：配额/跟进查询组合索引（列数即索引定义列数；resume 的 (user_id, job_description_id) 已由 V12 覆盖）
            assertEquals("3", scalar(statement, indexColumnCountSql(schema, "ai_task", "idx_ai_task_user_type_created")));
            assertEquals("2", scalar(statement, indexColumnCountSql(schema, "interview_ai_attempt", "idx_iai_user_created")));
            assertEquals("2", scalar(statement, indexColumnCountSql(schema, "application_record", "idx_application_user_followup")));
            assertEquals("2", scalar(statement, indexColumnCountSql(schema, "resume", "idx_resume_user_jd")));

            // V32：版本列表投影的派生列（历史行为 NULL，由服务端读路径惰性回填）
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "resume_version", "template_code")));

            // V33：AI 任务留存清理标记（NOT NULL DEFAULT false）
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "ai_task", "snapshot_purged")));
            assertEquals("0", scalar(statement, columnDefaultSql(schema, "ai_task", "snapshot_purged")));
        }
    }

    private String columnNullableSql(String schema, String table, String column) {
        return "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='" + table + "' AND column_name='" + column + "'";
    }

    private String tableCountSql(String schema, String table) {
        return "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + schema
                + "' AND table_name='" + table + "'";
    }

    private String columnDefaultSql(String schema, String table, String column) {
        return "SELECT COLUMN_DEFAULT FROM information_schema.columns WHERE table_schema='" + schema
                + "' AND table_name='" + table + "' AND column_name='" + column + "'";
    }

    private String uniqueAttemptIndexCountSql(String schema) {
        return "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='"
                + schema + "' AND table_name='interview_ai_attempt' AND non_unique=0"
                + " AND index_name IN ('uq_iai_user_idempotency','uq_iai_session_operation_round')";
    }

    private String indexColumnCountSql(String schema, String table, String indexName) {
        return "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='" + schema
                + "' AND table_name='" + table + "' AND index_name='" + indexName + "'";
    }

    private String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "Query returned no rows");
            return result.getString(1);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required for the live gate");
        return value;
    }
}
