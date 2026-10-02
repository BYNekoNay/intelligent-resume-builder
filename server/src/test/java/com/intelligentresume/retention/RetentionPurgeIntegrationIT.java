package com.intelligentresume.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分档清扫的**端到端**用例（决策 D2 阶段 1 + 阶段 2）：在真实 schema（test profile 的 H2）上
 * 造出真实行，再跑作业，断言**行级的最终状态**。
 *
 * <p>为什么必须有它（第六十八批补上阶段 1 的残留）：此前只有「候选 SQL 能在真实 schema 上执行」
 * 与「引用清单覆盖所有外键」两条静态守卫 —— 但**「DELETE/UPDATE 真的按预期改了数据吗」没有任何
 * 用例**。尤其 B 档要写 JSON 列，而 {@code UPDATE ... WHERE id = ?} 在**匹配 0 行时不会求值 SET
 * 表达式**，所以「0 行更新」的测试根本验证不了 JSON 写入 —— 必须造真实行。
 *
 * <p>断言一律按**语义**比对 JSON（用 Jackson 读节点），不比对字符串：同一份内容在 MySQL 与 H2
 * 的 JSON 文本形态不同（空格等）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionPurgeIntegrationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionPurgeService service;

    @Autowired
    private RetentionPurgeProperties properties;

    private boolean originalEnabled;
    private boolean originalDryRun;

    @BeforeEach
    void armJob() {
        originalEnabled = properties.isEnabled();
        originalDryRun = properties.isDryRun();
        properties.setEnabled(true);
        properties.setDryRun(false);
    }

    @AfterEach
    void restoreJob() {
        // 必须在每次用例后复原：Spring 上下文与 properties 是**共享**的，泄漏会把后续用例变成破坏性运行
        properties.setEnabled(originalEnabled);
        properties.setDryRun(originalDryRun);
    }

    @Test
    @DisplayName("A 档：无引用 + 超期 → 行被物理删除")
    void unreferencedExpiredVersion_isDeleted() {
        long user = insertUser("ret-a-1");
        long resume = insertResume(user, "R-A1", null);
        long version = insertVersion(resume, user, 1, "{\"basics\":{\"name\":\"Alice\"}}", null, expired());

        service.purgeExpiredSoftDeleted();

        assertEquals(0, count("resume_version", version), "无引用且超期的软删版本应被物理删除");
    }

    @Test
    @DisplayName("B 档：被引用 + 超期 → 行保留、正文快照化、元数据保留")
    void referencedExpiredVersion_isSnapshottedInPlace() throws Exception {
        long user = insertUser("ret-b-1");
        long resume = insertResume(user, "R-B1", null);
        long version = insertVersion(resume, user, 3, "{\"basics\":{\"name\":\"Bob\",\"email\":\"b@x.com\"}}",
                "{\"model\":\"qwen\"}", expired());
        // 制造引用：简历的当前版本指向它（resume.current_version_id → resume_version）
        jdbc.update("UPDATE resume SET current_version_id = ? WHERE id = ?", version, resume);

        service.purgeExpiredSoftDeleted();

        assertEquals(1, count("resume_version", version), "被引用的版本不得被物理删除（会撞外键、破坏历史）");
        assertEquals(version, id("SELECT current_version_id FROM resume WHERE id = ?", resume),
                "外键引用必须仍然成立");
        JsonNode resumeJson = readJson("SELECT resume_json FROM resume_version WHERE id = ?", version);
        assertTrue(resumeJson.path("__purged").asBoolean(), "正文应被替换为快照标记：" + resumeJson);
        assertNull(text("SELECT optimization_summary FROM resume_version WHERE id = ?", version),
                "摘要是 AI 生成正文，应被清空");
        assertNull(text("SELECT generation_context FROM resume_version WHERE id = ?", version),
                "生成上下文应被清空");
        // 元数据保留：审计需要「存在过、属于谁、何时删」
        assertEquals(3, id("SELECT version_no FROM resume_version WHERE id = ?", version));
        assertEquals("MANUAL", text("SELECT source_type FROM resume_version WHERE id = ?", version));
        assertEquals(resume, id("SELECT resume_id FROM resume_version WHERE id = ?", version));
        assertNotNull(text("SELECT deleted_at FROM resume_version WHERE id = ?", version),
                "deleted_at 必须保留（它是「已软删」的证据）");
    }

    @Test
    @DisplayName("B 档 · 职业资料：被引用 + 超期 → 内容与原文一并清空（含方案漏掉的 source_text）")
    void referencedExpiredMaterial_isSnapshotted() throws Exception {
        long user = insertUser("ret-b-2");
        // 引用者需要一条 resume_material_reference；其目标版本保持**未删除**，避免它自己进入清扫
        long resume = insertResume(user, "R-B2", null);
        long version = insertVersion(resume, user, 1, "{\"basics\":{}}", null, null);
        long material = insertMaterial(user, "WORK_EXPERIENCE", "星海科技 · 后端实习",
                "{\"company\":\"Acme\",\"role\":\"Dev\",\"metric\":\"QPS 提升 30%\"}",
                "原始粘贴文本：我在 Acme 担任后端实习生，QPS 提升 30%……", expired());
        jdbc.update("INSERT INTO resume_material_reference "
                        + "(resume_version_id, material_id, selection_status, output_path, source_snapshot_json, created_at) "
                        + "VALUES (?, ?, 'SELECTED', '$.work[0]', '{}', ?)",
                version, material, LocalDateTime.now());

        service.purgeExpiredSoftDeleted();

        assertEquals(1, count("career_material", material), "被引用的资料不得被物理删除");
        String rawContent = text("SELECT content_json FROM career_material WHERE id = ?", material);
        assertTrue(MAPPER.readTree(rawContent).path("__purged").asBoolean(),
                "内容 JSON 应被替换为快照标记：" + rawContent);
        assertNull(text("SELECT source_text FROM career_material WHERE id = ?", material),
                "source_text 是导入原文（MEDIUMTEXT，PII 载体），必须一并清空");
        assertEquals("星海科技 · 后端实习", text("SELECT title FROM career_material WHERE id = ?", material),
                "title 是用户给资料起的名字，保留");
        assertFalse(rawContent.contains("Acme") || rawContent.contains("QPS"),
                "PII 不得残留在内容列：" + rawContent);
    }

    @Test
    @DisplayName("未超期（仍在恢复期内）→ 不动：既不删也不快照")
    void notYetExpired_isUntouched() throws Exception {
        long user = insertUser("ret-c-1");
        long resume = insertResume(user, "R-C1", null);
        long version = insertVersion(resume, user, 1, "{\"basics\":{\"name\":\"Carol\"}}", null,
                LocalDateTime.now().minusDays(3));   // 恢复期（30 天）内
        jdbc.update("UPDATE resume SET current_version_id = ? WHERE id = ?", version, resume);

        service.purgeExpiredSoftDeleted();

        assertEquals(1, count("resume_version", version));
        assertFalse(readJson("SELECT resume_json FROM resume_version WHERE id = ?", version)
                        .path("__purged").asBoolean(),
                "恢复期内的版本不得被快照化（其正文仍可能被恢复使用）");
    }

    @Test
    @DisplayName("B 档幂等：连跑两次，快照内容逐字节一致（作业可安全重跑）")
    void snapshotIsIdempotent() {
        long user = insertUser("ret-d-1");
        long resume = insertResume(user, "R-D1", null);
        long version = insertVersion(resume, user, 1, "{\"basics\":{\"name\":\"Dave\"}}", null, expired());
        jdbc.update("UPDATE resume SET current_version_id = ? WHERE id = ?", version, resume);

        service.purgeExpiredSoftDeleted();
        String afterFirst = text("SELECT resume_json FROM resume_version WHERE id = ?", version);
        service.purgeExpiredSoftDeleted();
        String afterSecond = text("SELECT resume_json FROM resume_version WHERE id = ?", version);

        assertNotNull(afterFirst);
        assertEquals(afterFirst, afterSecond, "快照内容必须是常量/确定性派生，重跑结果一致");
    }

    // ---------- 夹具 ----------

    private LocalDateTime expired() {
        // recoveryDays(30) + graceDays(7) = 37 天；取 60 天确保超期
        return LocalDateTime.now().minusDays(60);
    }

    private long insertUser(String username) {
        return insert("INSERT INTO user (username, email, password_hash, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'ACTIVE', ?, ?)",
                username, username + "@example.com", LocalDateTime.now(), LocalDateTime.now());
    }

    private long insertResume(long userId, String title, LocalDateTime deletedAt) {
        return insert("INSERT INTO resume (user_id, title, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?)",
                userId, title, LocalDateTime.now(), LocalDateTime.now(), deletedAt);
    }

    private long insertVersion(long resumeId, long userId, int versionNo, String resumeJson,
                               String generationContext, LocalDateTime deletedAt) {
        return insert("INSERT INTO resume_version (resume_id, version_no, source_type, resume_json, "
                        + "optimization_summary, generation_context, created_by, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, 'MANUAL', CAST(? AS JSON), ?, CAST(? AS JSON), ?, ?, ?, ?)",
                resumeId, versionNo, resumeJson,
                deletedAt == null ? null : "AI 生成的优化摘要（可能含 PII）",
                generationContext, userId, LocalDateTime.now(), LocalDateTime.now(), deletedAt);
    }

    private long insertMaterial(long userId, String type, String title, String contentJson,
                                String sourceText, LocalDateTime deletedAt) {
        return insert("INSERT INTO career_material (user_id, material_type, title, content_json, source_text, "
                        + "usage_preference, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, CAST(? AS JSON), ?, 'NORMAL', ?, ?, ?)",
                userId, type, title, contentJson, sourceText, LocalDateTime.now(), LocalDateTime.now(), deletedAt);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        assertNotNull(key, "夹具插入未返回自增主键：" + sql);
        return key.longValue();
    }

    private int count(String table, long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private JsonNode readJson(String sql, Object... args) throws Exception {
        String raw = text(sql, args);
        assertNotNull(raw, "该行/列不应为空：" + sql);
        return MAPPER.readTree(raw);
    }
}
