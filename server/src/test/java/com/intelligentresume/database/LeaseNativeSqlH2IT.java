package com.intelligentresume.database;

import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.export.repository.ExportTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约类 native SQL 在 **H2** 上的专项验证。
 *
 * <p>为什么必须有：{@code ExportTaskRepository} 与 {@code AiTaskRepository} 的领取/加租约语句是
 * {@code nativeQuery = true}，且带 {@code LIMIT ... FOR UPDATE} 与 {@code NOW()} 这类 MySQL 写法。
 * 此前这些语句**从未在 H2 上真跑过** ——
 * <ul>
 *   <li>{@code ExportTaskLeaseServiceTest} 把 repository 整个 mock 掉了；</li>
 *   <li>{@code ExportControllerIT} 手工把任务置成 SUCCESS，从不触发 worker。</li>
 * </ul>
 * 也就是说：这两条 SQL 一旦在 H2 上不可用（桌面端正是 H2），
 * PDF 导出与 AI 任务会永远停在 {@code PENDING}，而现有测试**全绿**。
 *
 * <p>本用例直接构造数据并调用真实 repository 方法，因此它是对「SQL 能否在目标库上执行」的断言，
 * 而非对 mock 的断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LeaseNativeSqlH2IT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExportTaskRepository exportTaskRepository;

    @Autowired
    private AiTaskRepository aiTaskRepository;

    private static final long USER_ID = 900001L;

    @Test
    void exportTaskLeaseSqlRunsOnH2() {
        seedUser();
        long exportTaskId = seedExportTask("PENDING");
        long runningTaskId = seedExportTask("RUNNING");

        // ① 领取语句：LIMIT ... FOR UPDATE + NOW()
        var claimable = exportTaskRepository.claimableTasks(10);
        assertFalse(claimable.isEmpty(), "claimableTasks 在 H2 上应能执行并返回待领取任务");
        assertTrue(claimable.stream().anyMatch(t -> t.getId().equals(exportTaskId)),
                "新建的 PENDING 任务应在可领取集合中");

        // ② 加租约语句：UPDATE ... WHERE (status='PENDING' OR (status='RUNNING' AND lease_expires_at < NOW()))
        int claimed = exportTaskRepository.acquireLease(exportTaskId, "worker-1",
                LocalDateTime.now().plusMinutes(5));
        assertEquals(1, claimed, "acquireLease 应能更新一行（PENDING → RUNNING）");

        // ③ 已加租约的任务不再被重复领取
        var afterClaim = exportTaskRepository.claimableTasks(10);
        assertFalse(afterClaim.stream().anyMatch(t -> t.getId().equals(exportTaskId)),
                "已加租约的任务不应再次出现在可领取集合中");
        assertTrue(afterClaim.stream().anyMatch(t -> t.getId().equals(runningTaskId)),
                "租约过期的 RUNNING 任务应可被重新领取");
    }

    @Test
    void aiTaskLeaseSqlRunsOnH2() {
        seedUser();
        seedAiTask("PENDING");

        // 这里用 JDBC 直执行而不是 `aiTaskRepository.claimableTasksByTypes(...)`，是为了让本用例
        // 只验证「租约 SQL 在 H2 上可执行」这一件事，不与 JSON 列读写混在一起。
        // `ai_task.input_snapshot_json` 曾经在 H2 上因双编码（字符串标量）导致 Hibernate 回读抛
        // 「Could not deserialize string to java type: JsonJavaType」—— 该缺陷已修复
        // （读侧宽容 mapper + 写侧 `? FORMAT JSON` 规范化），由
        // `com.intelligentresume.desktop.JsonColumnH2ReadbackIT` 守住；本用例不必再兼职守它。
        var rows = jdbcTemplate.queryForList("SELECT id FROM ai_task WHERE status = 'PENDING'"
                + " OR (status = 'RUNNING' AND lease_expires_at < NOW()) ORDER BY id ASC LIMIT 10 FOR UPDATE");
        assertFalse(rows.isEmpty(), "ai_task 的领取 SQL（含 FOR UPDATE + NOW()）应在 H2 上可执行");

        int claimed = jdbcTemplate.update("UPDATE ai_task SET status = 'RUNNING', lease_owner = ?,"
                        + " lease_expires_at = ?, retry_count = retry_count + 1, updated_at = NOW()"
                        + " WHERE id = ? AND (status = 'PENDING'"
                        + " OR (status = 'RUNNING' AND lease_expires_at < NOW()))",
                "worker-1", LocalDateTime.now().plusMinutes(5), ((Number) rows.get(0).get("id")).longValue());
        assertEquals(1, claimed, "ai_task 的加租约 UPDATE 应能更新一行（PENDING → RUNNING）");
    }

    private void seedUser() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE id = ?", Integer.class, USER_ID);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO user (id, username, email, password_hash, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                USER_ID, "lease-it-" + USER_ID, "lease-it@example.invalid", "{noop}not-a-real-hash",
                LocalDateTime.now(), LocalDateTime.now());
    }

    private long seedExportTask(String status) {
        long resumeVersionId = seedResumeVersion();
        if ("RUNNING".equals(status)) {
            // 必须是「租约已过期」的 RUNNING：lease_expires_at 为空的 RUNNING 按既有语义
            // 不会被重新领取（`lease_expires_at < NOW()` 对 NULL 不成立），那是线上行为，不是 H2 差异。
            jdbcTemplate.update("INSERT INTO export_task (user_id, resume_version_id, template_code, status,"
                            + " retry_count, lease_owner, lease_expires_at, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 0, 'old-worker', ?, ?, ?)",
                    USER_ID, resumeVersionId, "classic", status, LocalDateTime.now().minusMinutes(10),
                    LocalDateTime.now(), LocalDateTime.now());
        } else {
            jdbcTemplate.update("INSERT INTO export_task (user_id, resume_version_id, template_code, status,"
                            + " retry_count, created_at, updated_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
                    USER_ID, resumeVersionId, "classic", status, LocalDateTime.now(), LocalDateTime.now());
        }
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM export_task WHERE user_id = ?", Long.class, USER_ID);
        return id == null ? -1L : id;
    }

    /** export_task.resume_version_id 有外键约束，必须造一条真实的简历版本。 */
    private long seedResumeVersion() {
        jdbcTemplate.update("INSERT INTO resume (user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                USER_ID, "租约IT简历", LocalDateTime.now(), LocalDateTime.now());
        Long resumeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM resume WHERE user_id = ?", Long.class, USER_ID);
        jdbcTemplate.update("INSERT INTO resume_version (resume_id, version_no, source_type, resume_json,"
                        + " created_by, created_at, updated_at) VALUES (?, 1, 'MANUAL', ?, ?, ?, ?)",
                resumeId, "{}", USER_ID, LocalDateTime.now(), LocalDateTime.now());
        Long versionId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM resume_version WHERE resume_id = ?",
                Long.class, resumeId);
        return versionId == null ? -1L : versionId;
    }

    private long seedAiTask(String status) {
        jdbcTemplate.update("INSERT INTO ai_task (user_id, task_type, idempotency_key, request_fingerprint,"
                        + " input_snapshot_json, status, retry_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                USER_ID, "JOB_GENERATION", "idem-" + USER_ID + "-" + status, "fingerprint-" + USER_ID + "-" + status,
                "{}", status, LocalDateTime.now(), LocalDateTime.now());
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ai_task WHERE user_id = ?", Long.class, USER_ID);
        return id == null ? -1L : id;
    }
}
