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
    void upgradesSupportedV19BaselineToV22OnMySql57() throws Exception {
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

            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "interview_session", "job_description_id")));
            assertEquals("YES", scalar(statement, columnNullableSql(schema, "interview_session", "current_question")));
            assertEquals("NO", scalar(statement, columnNullableSql(schema, "interview_session", "output_language")));
            assertEquals("ZH_CN", scalar(statement, columnDefaultSql(schema, "interview_session", "output_language")));
            assertEquals("1", scalar(statement, tableCountSql(schema, "interview_ai_attempt")));
            assertEquals("2", scalar(statement, uniqueAttemptIndexCountSql(schema)));
            assertEquals("22", scalar(statement,
                    "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1"));
            assertEquals("1", scalar(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='19' AND type='BASELINE' AND success=1"));
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
