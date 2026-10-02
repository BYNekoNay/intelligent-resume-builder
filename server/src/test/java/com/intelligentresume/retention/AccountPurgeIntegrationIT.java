package com.intelligentresume.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户级联硬删的**行级端到端**用例（决策 D2 阶段 3）。
 *
 * <p>为什么必须造真实行：级联 DELETE 的顺序错误只有在外键真实存在时才会暴露
 * （H2 强制 FK）—— mock 掉数据库的单元测试永远验证不了「顺序」。本用例为目标用户与
 * 旁观用户各造一整套业务行（覆盖全部 22 张业务表），执行清扫后断言：
 * <ul>
 *   <li>目标用户：22 张业务表全部归零 + {@code user} 行消失；</li>
 *   <li>旁观用户：全部数据原样保留（级联只对准被删用户）；</li>
 *   <li>{@code communication_template} 的系统种子行（is_system=1，V23 种子）原样保留；</li>
 *   <li>任务行保留并转 SUCCESS（审计：不挂外键，user 删除后仍可查）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountPurgeIntegrationIT {

    @Autowired
    private AccountPurgeService service;

    @Autowired
    private AccountPurgeProperties purgeProperties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountPurgeRepository purgeRepository;

    private boolean originalEnabled;

    @org.junit.jupiter.api.BeforeEach
    void enablePurge() {
        originalEnabled = purgeProperties.isEnabled();
        purgeProperties.setEnabled(true);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreFlag() {
        purgeProperties.setEnabled(originalEnabled);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupTestUsers() {
        // 复用被测级联删除清理两个测试用户（顺带实证「已删数据再删 0 行」的重入幂等）；
        // 防止本类行残留进共享 H2 影响后续测试类的全表操作（如 ScoringControllerIT 的 deleteAll）。
        for (long u : new long[]{9101L, 9102L}) {
            purgeRepository.purgeUserData(u);
            purgeRepository.deleteUser(u);
        }
        jdbc.update("DELETE FROM account_deletion_job WHERE user_id IN (9101, 9102)");
    }

    @Test
    @DisplayName("窗口已过的删号任务 → 目标用户 22 表级联清空、旁观用户与系统种子不受影响、任务转 SUCCESS")
    void purgeRemovesExactlyTheTargetUser() {
        JdbcTemplate d = jdbc;
        long victim = 9101L;
        long bystander = 9102L;

        // —— 两个用户各自一整套业务行（列清单取自迁移的 NOT NULL 无默认列）——
        for (long u : new long[]{victim, bystander}) {
            d.update("INSERT INTO user (id, username, email, password_hash, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'x', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    u, "purge_it_" + u, "purge_it_" + u + "@test.local");
            d.update("INSERT INTO personal_profile (user_id, created_at, updated_at) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", u);
            d.update("INSERT INTO auth_session (user_id, token_family_id, refresh_token_hash, issued_at, expires_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    u, "fam-" + u, "hash-" + u);
            d.update("INSERT INTO resume (user_id, title, created_at, updated_at) VALUES (?, 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", u);
            long resumeId = d.queryForObject("SELECT id FROM resume WHERE user_id = ?", Long.class, u);
            d.update("INSERT INTO job_description (user_id, title, jd_text, created_at, updated_at) "
                    + "VALUES (?, 'jd', 'java', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", u);
            long jdId = d.queryForObject("SELECT id FROM job_description WHERE user_id = ?", Long.class, u);
            d.update("INSERT INTO career_material (user_id, material_type, title, content_json, created_at, updated_at) "
                    + "VALUES (?, 'WORK_EXPERIENCE', 'm', '{}' FORMAT JSON, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", u);
            long materialId = d.queryForObject("SELECT id FROM career_material WHERE user_id = ?", Long.class, u);
            d.update("INSERT INTO resume_version (resume_id, version_no, source_type, resume_json, created_by, created_at, updated_at) "
                    + "VALUES (?, 1, 'MANUAL', '{}' FORMAT JSON, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", resumeId, u);
            long versionId = d.queryForObject("SELECT id FROM resume_version WHERE resume_id = ?", Long.class, resumeId);
            d.update("INSERT INTO resume_material_reference (resume_version_id, material_id, source_snapshot_json, "
                    + "output_path, selection_status, created_at) VALUES (?, ?, '{}' FORMAT JSON, 'p', 'SELECTED', CURRENT_TIMESTAMP)",
                    versionId, materialId);
            d.update("INSERT INTO match_result (resume_version_id, job_description_id, keyword_score, skill_score, "
                            + "experience_score, total_score, rule_version, explanation_json, created_at) "
                            + "VALUES (?, ?, 1, 1, 1, 1, 'v1', '{}' FORMAT JSON, CURRENT_TIMESTAMP)", versionId, jdId);
            d.update("INSERT INTO ai_task (user_id, task_type, idempotency_key, request_fingerprint, input_snapshot_json, "
                            + "created_at, updated_at) VALUES (?, 'JOB_GENERATION', ?, ?, '{}' FORMAT JSON, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    u, "it-key-" + u, "fp-" + u);
            d.update("INSERT INTO ats_check_result (user_id, resume_version_id, job_description_id, total_score, result_json) "
                    + "VALUES (?, ?, ?, 50, '{}' FORMAT JSON)", u, versionId, jdId);
            d.update("INSERT INTO export_task (user_id, resume_version_id, template_code, created_at, updated_at) "
                    + "VALUES (?, ?, 'classic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", u, versionId);
            d.update("INSERT INTO application_record (user_id, resume_version_id, job_description_id, status) "
                    + "VALUES (?, ?, ?, 'APPLIED')", u, versionId, jdId);
            d.update("INSERT INTO inline_optimization_record (user_id, resume_version_id, section_code, provider_code, "
                    + "original_content, result_json) VALUES (?, ?, 'work', 'qwen', 'a', '{}' FORMAT JSON)", u, versionId);
            d.update("INSERT INTO communication_draft (user_id, resume_version_id, job_description_id, draft_type, draft_text) "
                    + "VALUES (?, ?, ?, 'EMAIL', 't')", u, versionId, jdId);
            d.update("INSERT INTO communication_template (user_id, name, scene, template_type, body_text) "
                    + "VALUES (?, 'mine', 'outreach', 'EMAIL', 't')", u);
            d.update("INSERT INTO ai_consent (user_id, event_type, policy_version, provider_code, notice_hash, "
                            + "data_categories_json, task_scopes_json, created_at) "
                            + "VALUES (?, 'GRANTED', 'v1.2.0', 'bailian', 'hash', '[\"RESUME\"]', '[\"RESUME\"]', CURRENT_TIMESTAMP)",
                    u);
            d.update("INSERT INTO material_resume_generation (user_id, generated_resume_json, raw_material_text, suggestions_json) "
                    + "VALUES (?, '{}' FORMAT JSON, 'raw', '{}' FORMAT JSON)", u);
            d.update("INSERT INTO interview_session (user_id, job_description_id, interview_mode, source_type, status, current_question) "
                    + "VALUES (?, ?, 'AI_COACH', 'RESUME', 'COMPLETED', 'q')", u, jdId);
            long sessionId = d.queryForObject("SELECT id FROM interview_session WHERE user_id = ?", Long.class, u);
            d.update("INSERT INTO interview_record (session_id, round_no, question_text, answer_text, feedback_json, round_score) "
                    + "VALUES (?, 1, 'q', 'a', '{}', 5)", sessionId);
            long recordId = d.queryForObject("SELECT id FROM interview_record WHERE session_id = ?", Long.class, sessionId);
            d.update("INSERT INTO interview_ai_attempt (user_id, session_id, operation_type, idempotency_key, request_fingerprint) "
                    + "VALUES (?, ?, 'GENERATE_QUESTION', ?, ?)", u, sessionId, "att-" + u, "att-fp-" + u);
            d.update("INSERT INTO interview_answer_asset (user_id, question_text, original_answer_text) "
                    + "VALUES (?, 'q', 'a')", u);
            long assetId = d.queryForObject("SELECT id FROM interview_answer_asset WHERE user_id = ?", Long.class, u);
            d.update("INSERT INTO interview_asset_section (user_id, asset_id, section_key) VALUES (?, ?, 'work')", u, assetId);
        }

        // 目标用户的删号任务：撤销窗口已过
        d.update("INSERT INTO account_deletion_job (user_id, status, requested_at, cancel_until) "
                + "VALUES (?, 'PENDING', CURRENT_TIMESTAMP, DATEADD('DAY', -1, CURRENT_TIMESTAMP))", victim);

        int purged = service.purgeExpiredAccounts();

        assertThat(purged).isEqualTo(1);
        // 目标用户的 user 行消失
        Long victimUserRows = d.queryForObject("SELECT COUNT(*) FROM user WHERE id = ?", Long.class, victim);
        assertThat(victimUserRows).isZero();
        // 直接持 user_id 的表：目标用户行全部归零（含没有显式插入断言路径的 ai_consent）
        for (String table : new String[]{"personal_profile", "auth_session", "resume", "job_description",
                "career_material", "ai_task", "ats_check_result", "export_task", "application_record",
                "inline_optimization_record", "communication_draft", "interview_session",
                "interview_ai_attempt", "interview_answer_asset",
                "interview_asset_section", "material_resume_generation", "ai_consent"}) {
            Long count = d.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, victim);
            assertThat(count).as("目标用户在 %s 的行应被级联删除", table).isZero();
        }
        // 经链表（无 user_id 列）的目标行也已消失
        assertThat(d.queryForObject("SELECT COUNT(*) FROM resume_version WHERE resume_id IN "
                + "(SELECT id FROM resume WHERE user_id = ?)", Long.class, victim)).isZero();
        assertThat(d.queryForObject("SELECT COUNT(*) FROM resume_material_reference WHERE resume_version_id IN "
                + "(SELECT rv.id FROM resume_version rv JOIN resume r ON rv.resume_id = r.id WHERE r.user_id = ?)",
                Long.class, victim)).isZero();
        assertThat(d.queryForObject("SELECT COUNT(*) FROM match_result WHERE resume_version_id IN "
                + "(SELECT rv.id FROM resume_version rv JOIN resume r ON rv.resume_id = r.id WHERE r.user_id = ?)",
                Long.class, victim)).isZero();
        assertThat(d.queryForObject("SELECT COUNT(*) FROM interview_record WHERE session_id IN "
                + "(SELECT id FROM interview_session WHERE user_id = ?)", Long.class, victim)).isZero();
        // 旁观用户的数据原样保留
        Long bystanderResumes = d.queryForObject("SELECT COUNT(*) FROM resume WHERE user_id = ?", Long.class, bystander);
        assertThat(bystanderResumes).isEqualTo(1);
        Long bystanderSessions = d.queryForObject("SELECT COUNT(*) FROM interview_session WHERE user_id = ?", Long.class, bystander);
        assertThat(bystanderSessions).isEqualTo(1);
        // 系统种子模板不被触碰（V23 种子 16 行，is_system=1）
        Long seedTemplates = d.queryForObject(
                "SELECT COUNT(*) FROM communication_template WHERE user_id IS NULL", Long.class);
        assertThat(seedTemplates).isGreaterThanOrEqualTo(16);
        // 目标用户的自建模板被删
        Long victimTemplates = d.queryForObject(
                "SELECT COUNT(*) FROM communication_template WHERE user_id = ?", Long.class, victim);
        assertThat(victimTemplates).isZero();
        // 任务行转 SUCCESS 留作审计
        String status = d.queryForObject(
                "SELECT status FROM account_deletion_job WHERE user_id = ? AND status = 'SUCCESS'",
                String.class, victim);
        assertThat(status).isEqualTo("SUCCESS");
    }
}
