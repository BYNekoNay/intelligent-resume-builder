package com.intelligentresume.desktop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #77：apply 会重新 plan —— 预览时判 CONFLICT、apply 时因本地内容已一致而判 SKIP 的记录，
 * 用户给出的 resolution 会被 {@code DesktopImportWriter} 的 SKIP 分支忽略。
 * 本类锁定的是**可见性**：响应必须把这些记录放进 {@code skippedAfterConflict} 点名出来，
 * 而不是静默吞掉用户的决定（SKIP 行为本身保持不变，不改产品语义）。
 *
 * <p>单独成类的原因：{@link DesktopImportServiceIT} 已被源文件行数棘轮登记，
 * 只许下调不许上涨（见 SourceFileSizeRatchetContractTest），新场景按棘轮要求拆到这里。
 */
@SpringBootTest
@ActiveProfiles("test")
class DesktopImportSkipVisibilityIT {

    private static final String DATA_DIR = "target/import-skip-it-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String absolute = Path.of(DATA_DIR).toAbsolutePath().toString().replace('\\', '/');
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + absolute + "/resume"
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE");
        registry.add("app.desktop.enabled", () -> "true");
        registry.add("app.desktop.data-dir", () -> absolute);
    }

    @Autowired
    private DesktopImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 从干净状态开始：判据只看映射表，清映射即回到初始态（口径同 DesktopImportServiceIT）。 */
    @BeforeEach
    void forgetMappingsWrittenByPreviousTests() {
        jdbcTemplate.update("DELETE FROM desktop_import_map");
    }

    @Test
    void resolutionForRecordThatTurnsSkipAtApplyIsReportedNotSilentlyIgnored() {
        seedLocalJobDescription(516, "Java Backend legacy");
        rememberMapping(516L, "web:jobDescriptions:516");

        // 预览：内容变了 → 冲突；用户会为此给出 resolution
        Map<String, Object> changed = packageOf(jobDescription(516, "Java Backend (changed)"));
        assertEquals(1, importService.preview(changed).conflicts(), "预览阶段这条必须判为冲突");

        // 预览到 apply 之间本地内容被改成与包一致（如另一端写入）→ apply 重新 plan 时判 SKIP
        jdbcTemplate.update("UPDATE job_description SET title = ? WHERE id = ?",
                "Java Backend (changed)", 516L);

        DesktopImportService.ImportResult result = importService.apply(changed,
                Map.<String, Object>of("web:jobDescriptions:516", "USE_IMPORTED"));
        assertEquals(0, result.updated(), "SKIP 分支不应用用户的 USE_IMPORTED（行为保持不变）");
        assertEquals(1, result.skippedAfterConflict().size(), "被 SKIP 且给过 resolution 的记录必须出现在响应里");

        DesktopImportService.SkippedAfterConflict item = result.skippedAfterConflict().get(0);
        assertEquals("web:jobDescriptions:516", item.stableId());
        assertEquals("jobDescriptions", item.collection());
        assertEquals("job_description", item.entityType());
        assertTrue(item.reason().contains("内容一致"), "reason 应说明跳过原因，实际：" + item.reason());
    }

    // ------------------------------------------------------------------ 包构造与本地种子

    private Map<String, Object> jobDescription(int id, String title) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("stableId", "web:jobDescriptions:" + id);
        record.put("title", title);
        record.put("companyName", "Example Corp");
        record.put("jdText", "Java and Spring Boot");
        record.put("createdAt", "2026-01-01T10:00:00");
        record.put("updatedAt", "2026-01-01T10:00:00");
        return record;
    }

    private Map<String, Object> packageOf(Map<String, Object> record) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageId", "web-export-test");
        manifest.put("source", "web");
        manifest.put("recordCount", 1);
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("formatVersion", 3);
        pkg.put("source", "web");
        pkg.put("manifest", manifest);
        pkg.put("jobDescriptions", List.of(record));
        return pkg;
    }

    private void seedLocalJobDescription(int id, String title) {
        jdbcTemplate.update("INSERT INTO job_description (id, user_id, title, company_name, jd_text,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, 1L, title, "Example Corp", "Java and Spring Boot");
    }

    private void rememberMapping(Long localId, String stableId) {
        jdbcTemplate.update("INSERT INTO desktop_import_map (user_id, entity_type, local_id, source_stable_id, imported_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                1L, "job_description", localId, stableId);
    }
}
