package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopModeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web → 桌面迁移包的**分类**逻辑（ADR-012 阶段四 / R29 / R37）。
 *
 * <p>这里锁定的是 R37 的三条判据：
 * <ul>
 *   <li>相同稳定 ID + 相同内容 → <b>跳过</b>；</li>
 *   <li>相同稳定 ID + 内容变了 → <b>冲突</b>（交给用户解决，不自动合并）；</li>
 *   <li>不同稳定 ID，哪怕内容几乎一样 → <b>仍然视为新增</b>（不按文本相似度自动合并）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class DesktopImportServiceIT {

    private static final String DATA_DIR = "target/import-it-" + UUID.randomUUID().toString().substring(0, 8);

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

    @Autowired
    private DesktopModeProperties properties;

    @Test
    void unknownRecordIsClassifiedAsAdd() {
        DesktopImportService.ImportPreview preview = importService.preview(packageOf(jobDescription(77, "Java 后端")));
        assertEquals(1, preview.added());
        assertEquals(0, preview.skipped());
        assertEquals(0, preview.conflicts());
    }

    @Test
    void sameStableIdAndSameContentIsSkipped() {
        // 先在本地留下一条映射与同内容记录
        Map<String, Object> record = jobDescription(77, "Java 后端");
        seedLocalJobDescription(77, "Java 后端");
        rememberMapping("job_description", 77L, stableIdOf(record));

        DesktopImportService.ImportPreview preview = importService.preview(packageOf(record));
        assertEquals(0, preview.added());
        assertEquals(1, preview.skipped(), "内容一致时应跳过");
        assertEquals(0, preview.conflicts());
    }

    @Test
    void sameStableIdButChangedContentIsConflict() {
        seedLocalJobDescription(77, "Java 后端");
        rememberMapping("job_description", 77L, stableIdOf(jobDescription(77, "Java 后端")));

        DesktopImportService.ImportPreview preview =
                importService.preview(packageOf(jobDescription(77, "Java 后端（已改）")));
        assertEquals(0, preview.added());
        assertEquals(0, preview.skipped());
        assertEquals(1, preview.conflicts(), "同 ID 内容变化必须判为冲突而不是自动覆盖");
    }

    @Test
    void differentStableIdWithAlmostSameContentIsStillAnAddition() {
        // 本地有 id=77 的记录，但来源稳定 ID 不同 —— 按内容相似度合并是错的（R37）
        seedLocalJobDescription(77, "Java 后端");

        DesktopImportService.ImportPreview preview = importService.preview(packageOf(jobDescription(78, "Java 后端")));
        assertEquals(1, preview.added(), "不同稳定 ID 即便内容几乎一样，也应视为独立记录");
        assertEquals(0, preview.conflicts());
    }

    @Test
    void packageWithoutManifestIsRejected() {
        Map<String, Object> pkg = Map.of("formatVersion", 3,
                "jobDescriptions", List.of(jobDescription(1, "x")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> importService.preview(pkg));
        assertTrue(failure.getMessage().contains("manifest"),
                "缺 manifest 应被明确拒绝：" + failure.getMessage());
    }

    @Test
    void packageWithUnsupportedCollectionsIsReportedNotSilentlyDropped() {
        Map<String, Object> pkg = new java.util.LinkedHashMap<>(packageOf(jobDescription(1, "x")));
        pkg.put("interviewSessions", List.of(Map.of("id", 9, "stableId", "web:interviewSessions:9")));

        DesktopImportService.ImportPreview preview = importService.preview(pkg);
        assertTrue(preview.unsupportedCollections().contains("interviewSessions"),
                "本版不支持的集合必须被点名，而不是静默丢弃：" + preview.unsupportedCollections());
    }

    // ------------------------------------------------------------------ 辅助

    private Map<String, Object> packageOf(Map<String, Object>... records) {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("packageId", "web-export-test");
        manifest.put("source", "web");
        manifest.put("recordCount", records.length);
        manifest.put("collections", Map.of("jobDescriptions",
                Map.of("count", records.length, "stableIds", List.of(stableIdOf(records[0])))));
        Map<String, Object> pkg = new java.util.LinkedHashMap<>();
        pkg.put("formatVersion", 3);
        pkg.put("source", "web");
        pkg.put("manifest", manifest);
        pkg.put("jobDescriptions", List.of(records));
        return pkg;
    }

    private Map<String, Object> jobDescription(int id, String title) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id);
        record.put("stableId", "web:jobDescriptions:" + id);
        record.put("title", title);
        record.put("companyName", "示例公司");
        record.put("jdText", "熟悉 Java 与 Spring Boot");
        return record;
    }

    private String stableIdOf(Map<String, Object> record) {
        return String.valueOf(record.get("stableId"));
    }

    private void seedLocalJobDescription(int id, String title) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_description WHERE id = ?", Integer.class, id);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO job_description (id, user_id, title, company_name, jd_text,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, 1L, title, "示例公司", "熟悉 Java 与 Spring Boot");
    }

    private void rememberMapping(String entityType, Long localId, String stableId) {
        // 同一 file 库被本类的多个用例共享，先清后插，避免上一条用例留下的映射触发唯一键冲突
        jdbcTemplate.update("DELETE FROM desktop_import_map WHERE source_stable_id = ?", stableId);
        jdbcTemplate.update("INSERT INTO desktop_import_map (user_id, entity_type, local_id, source_stable_id, imported_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                1L, entityType, localId, stableId);
    }
}
