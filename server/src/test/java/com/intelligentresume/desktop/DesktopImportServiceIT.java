package com.intelligentresume.desktop;

import com.intelligentresume.config.DesktopModeProperties;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 每个用例从干净状态开始：本类的用例共享同一个 H2 file 库（见 {@link #properties}），
     * 上一个用例写下的映射会让下一个用例「本地没有这条记录」的前提不成立。
     *
     * <p>必须清的是映射表而不是数据行：{@code preview}/{@code apply} 的
     * ADD/SKIP/CONFLICT 判据**只看**「本地有没有这条 stableId 的映射」
     * （见 {@code DesktopImportService#localIdOf}），所以清掉映射就等于把判据拉回初始态，
     * 且不必触碰有环外键（resume ↔ resume_version）的数据行。
     */
    @BeforeEach
    void forgetMappingsWrittenByPreviousTests() {
        List<String> stableIds = new java.util.ArrayList<>(
                List.of("web:jobDescriptions:77", "web:jobDescriptions:78"));
        for (int base : PACKAGE_BASES) {
            stableIds.add("web:jobDescriptions:" + base);
            stableIds.add("web:resumes:" + (base + 100));
            stableIds.add("web:resumes:" + (base + 100) + ":versions:" + (base + 101));
            stableIds.add("web:applications:" + (base + 200));
            stableIds.add("web:applications:" + (base + 200) + ":statusHistory:3");
        }
        for (String stableId : stableIds) {
            jdbcTemplate.update("DELETE FROM desktop_import_map WHERE source_stable_id = ?", stableId);
        }
    }

    @Test
    void unknownRecordIsClassifiedAsAdd() {
        DesktopImportService.ImportPreview preview = importService.preview(packageOf(jobDescription(77, "Java Backend legacy")));
        assertEquals(1, preview.added());
        assertEquals(0, preview.skipped());
        assertEquals(0, preview.conflicts());
    }

    @Test
    void sameStableIdAndSameContentIsSkipped() {
        // 先在本地留下一条映射与同内容记录
        Map<String, Object> record = jobDescription(77, "Java Backend legacy");
        seedLocalJobDescription(77, "Java Backend legacy");
        rememberMapping("job_description", 77L, stableIdOf(record));

        DesktopImportService.ImportPreview preview = importService.preview(packageOf(record));
        assertEquals(0, preview.added());
        assertEquals(1, preview.skipped(), "内容一致时应跳过");
        assertEquals(0, preview.conflicts());
    }

    @Test
    void sameStableIdButChangedContentIsConflict() {
        seedLocalJobDescription(77, "Java Backend legacy");
        rememberMapping("job_description", 77L, stableIdOf(jobDescription(77, "Java Backend legacy")));

        DesktopImportService.ImportPreview preview =
                importService.preview(packageOf(jobDescription(77, "Java 后端（已改）")));
        assertEquals(0, preview.added());
        assertEquals(0, preview.skipped());
        assertEquals(1, preview.conflicts(), "同 ID 内容变化必须判为冲突而不是自动覆盖");
    }

    @Test
    void differentStableIdWithAlmostSameContentIsStillAnAddition() {
        // 本地有 id=77 的记录，但来源稳定 ID 不同 —— 按内容相似度合并是错的（R37）
        seedLocalJobDescription(77, "Java Backend legacy");

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


    @Test
    void applyWritesRecordsAndRemapsForeignKeysToLocalIds() {
        Map<String, Object> pkg = fullPackage(501);

        DesktopImportService.ImportResult result = importService.apply(pkg, Map.<String, Object>of());
        // 不断言全局 inserted 计数：同一个 file 库里其它用例的残留会让计数不可靠；
        // 真正要证明的是「记录写进去了，且外键指向本地 ID」。
        assertTrue(result.inserted() >= 1, "应至少写入一条记录");

        Long localJd = localIdOfStableId("web:jobDescriptions:501");
        Long localResume = localIdOfStableId("web:resumes:601");
        Long localVersion = localIdOfStableId("web:resumes:601:versions:602");
        Long localApplication = localIdOfStableId("web:applications:701");

        assertNotNull(localJd);
        assertNotNull(localResume);
        assertNotNull(localVersion);
        assertNotNull(localApplication);
        // Web 侧主键是 5 / 7 / 11，本地主键是自增的 —— 外键必须指向本地 ID
        assertNotEquals(5L, localJd, "本地主键不应沿用 Web 侧主键");

        Long jdFk = jdbcTemplate.queryForObject(
                "SELECT job_description_id FROM application_record WHERE id = ?", Long.class, localApplication);
        Long versionFk = jdbcTemplate.queryForObject(
                "SELECT resume_version_id FROM application_record WHERE id = ?", Long.class, localApplication);
        Long resumeFk = jdbcTemplate.queryForObject(
                "SELECT resume_id FROM resume_version WHERE id = ?", Long.class, localVersion);
        Long historyFk = jdbcTemplate.queryForObject(
                "SELECT application_record_id FROM application_status_history WHERE application_record_id = ?",
                Long.class, localApplication);

        assertEquals(localJd, jdFk, "投递记录的 JD 外键必须指向本地岗位 ID");
        assertEquals(localVersion, versionFk, "投递记录的简历版本外键必须指向本地版本 ID");
        assertEquals(localResume, resumeFk, "简历版本的外键必须指向本地简历 ID");
        assertEquals(localApplication, historyFk, "状态历史的外键必须指向本地投递 ID");
    }

    @Test
    void applyingTheSamePackageTwiceSkipsEverything() {
        Map<String, Object> pkg = fullPackage(502);
        importService.apply(pkg, Map.<String, Object>of());

        DesktopImportService.ImportResult second = importService.apply(pkg, Map.<String, Object>of());
        assertEquals(0, second.inserted(), "重复导入不应再写入");
        assertEquals(3, second.skipped(), "重复导入应全部跳过");

        Integer jdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM desktop_import_map WHERE source_stable_id = ?",
                Integer.class, "web:jobDescriptions:502");
        assertEquals(1, jdCount, "重复导入不能产生重复记录");
    }

    @Test
    void conflictCanBeResolvedByUsingTheImportedVersion() {
        Map<String, Object> pkg = fullPackage(503);
        importService.apply(pkg, Map.<String, Object>of());
        Long localId = localIdOfStableId("web:jobDescriptions:503");
        assertNotNull(localId, "首次导入必须留下「稳定 ID → 本地 ID」的映射");
        assertEquals("Java Backend-503", titleOfJobDescription(localId),
                "首次导入后本地记录应是导入版本的内容");

        // 同一 stableId，但内容变了 → 冲突
        Map<String, Object> changed = fullPackage(503);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jds = (List<Map<String, Object>>) changed.get("jobDescriptions");
        jds.get(0).put("title", "Java Backend (changed)");

        DesktopImportService.ImportPreview preview = importService.preview(changed);
        assertEquals(1, preview.conflicts(), "内容变化应判为冲突");
        assertEquals(0, preview.added(), "已有映射的记录不该被重复算成新增");
        assertEquals("Java Backend-503", titleOfJobDescription(localId),
                "预览阶段不得改动本地记录（R38：先预览、用户确认后才写）");

        DesktopImportService.ImportResult result = importService.apply(changed,
                Map.<String, Object>of("web:jobDescriptions:503", "USE_IMPORTED"));
        assertEquals(1, result.updated(), "USE_IMPORTED 应覆盖本地那条");
        assertEquals(0, result.inserted(), "冲突解决是覆盖本地记录，不是再插一份");

        assertEquals("Java Backend (changed)", titleOfJobDescription(localId),
                "USE_IMPORTED 后本地那条必须是导入版本的内容");
        Integer localRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_description WHERE id = ?", Integer.class, localId);
        assertEquals(1, localRows, "本地记录应仍在（被导入版本覆盖），而不是被删掉或复制");
        assertEquals(localId, localIdOfStableId("web:jobDescriptions:503"),
                "覆盖不得改变「稳定 ID → 本地 ID」的映射");
    }

    @Test
    void keepingLocalLeavesTheLocalRecordUntouched() {
        Map<String, Object> pkg = fullPackage(504);
        importService.apply(pkg, Map.<String, Object>of());
        Long localId = localIdOfStableId("web:jobDescriptions:504");
        assertNotNull(localId, "首次导入必须留下「稳定 ID → 本地 ID」的映射");
        assertEquals("Java Backend-504", titleOfJobDescription(localId),
                "首次导入后本地记录应是导入版本的内容");

        Map<String, Object> changed = fullPackage(504);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jds = (List<Map<String, Object>>) changed.get("jobDescriptions");
        jds.get(0).put("title", "Java Backend (changed)");

        DesktopImportService.ImportResult result = importService.apply(changed,
                Map.<String, Object>of("web:jobDescriptions:504", "KEEP_LOCAL"));
        assertEquals(1, result.keptLocal(), "KEEP_LOCAL 应保留本地记录");
        assertEquals(0, result.updated(), "KEEP_LOCAL 不得改动本地记录");
        assertEquals(0, result.inserted(), "KEEP_LOCAL 不得写入导入版本");

        // 用映射里的本地 ID 定位那条记录（不用中文字面量做查询键，也不按标题反查）
        assertEquals("Java Backend-504", titleOfJobDescription(localId),
                "KEEP_LOCAL 后本地标题必须仍是导入前的原文");
        Integer overwritten = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_description WHERE id = ? AND title = ?",
                Integer.class, localId, "Java Backend (changed)");
        assertEquals(0, overwritten, "KEEP_LOCAL 不得把本地记录改成导入版本的标题");
        assertEquals(localId, localIdOfStableId("web:jobDescriptions:504"),
                "KEEP_LOCAL 不得改变「稳定 ID → 本地 ID」的映射");
    }

    // ------------------------------------------------------------------ 包构造

    /** 各用例专属的主键段（{@code fullPackage} 的 base），也是 {@link #forgetMappingsWrittenByPreviousTests} 的清理范围。 */
    private static final int[] PACKAGE_BASES = {501, 502, 503, 504};

    /**
     * 构造一个工作区包。{@code base} 让每个用例用**专属的主键段** ——
     * 本类的用例共享同一个 H2 file 库，主键撞车会让「本地为空」的前提不成立。
     */
    private Map<String, Object> fullPackage(int base) {
        Map<String, Object> pkg = new java.util.LinkedHashMap<>();
        pkg.put("formatVersion", 3);
        pkg.put("source", "web");
        pkg.put("manifest", Map.of("packageId", "web-export-test", "source", "web", "recordCount", 3));
        pkg.put("jobDescriptions", List.of(jobDescription(base, "Java Backend-" + base)));
        pkg.put("resumes", List.of(resume(base + 100, base + 101)));
        pkg.put("applications", List.of(application(base + 200, base, base + 100, base + 101)));
        return pkg;
    }

    private Map<String, Object> jobDescription(int id, String title) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id);
        record.put("stableId", "web:jobDescriptions:" + id);
        record.put("title", title);
        record.put("companyName", "Example Corp");
        record.put("jdText", "Java and Spring Boot");
        record.put("createdAt", "2026-01-01T10:00:00");
        record.put("updatedAt", "2026-01-01T10:00:00");
        return record;
    }

    private Map<String, Object> resume(int id, int versionId) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id);
        record.put("stableId", "web:resumes:" + id);
        record.put("title", "Backend Resume-" + id);
        record.put("currentVersionId", versionId);
        record.put("createdAt", "2026-01-01T10:00:00");
        record.put("updatedAt", "2026-01-01T10:00:00");
        Map<String, Object> version = new java.util.LinkedHashMap<>();
        version.put("id", versionId);
        version.put("stableId", "web:resumes:" + id + ":versions:" + versionId);
        version.put("versionNo", 1);
        version.put("sourceType", "MANUAL");
        version.put("resumeJson", "{\"basics\":{\"name\":\"张三\"}}");
        version.put("createdBy", 1);
        version.put("createdAt", "2026-01-01T10:00:00");
        version.put("updatedAt", "2026-01-01T10:00:00");
        record.put("versions", List.of(version));
        return record;
    }

    private Map<String, Object> application(int id, int jdId, int resumeId, int versionId) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("id", id);
        record.put("stableId", "web:applications:" + id);
        record.put("companyName", "Example Corp");
        record.put("position", "Backend Engineer");
        record.put("status", "APPLIED");
        record.put("jobDescriptionId", jdId);
        record.put("resumeId", resumeId);
        record.put("resumeVersionId", versionId);
        record.put("createdAt", "2026-01-01T10:00:00");
        record.put("updatedAt", "2026-01-01T10:00:00");
        Map<String, Object> history = new java.util.LinkedHashMap<>();
        history.put("id", 3);
        history.put("stableId", "web:applications:" + id + ":statusHistory:3");
        // 状态历史表的列是 from_status / to_status（导出时也是这两个实体属性名）
        history.put("fromStatus", null);
        history.put("toStatus", "APPLIED");
        history.put("changedAt", "2026-01-01T10:00:00");
        record.put("statusHistory", List.of(history));
        return record;
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
                id, 1L, title, "Example Corp", "Java and Spring Boot");
    }

    /** 用稳定 ID 查本地 ID（避免用中文字面量做断言键）。 */
    private Long localIdOfStableId(String stableId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT local_id FROM desktop_import_map WHERE source_stable_id = ?", Long.class, stableId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 按本地 ID 读岗位标题 —— 冲突解决的断言必须压在**这条记录的内容**上，
     * 而不是「按标题反查有几行」（后者在标题写错时永远数不到行，是假的通过）。
     */
    private String titleOfJobDescription(Long localId) {
        List<String> titles = jdbcTemplate.queryForList(
                "SELECT title FROM job_description WHERE id = ?", String.class, localId);
        return titles.isEmpty() ? null : titles.get(0);
    }

    private void rememberMapping(String entityType, Long localId, String stableId) {
        // 同一 file 库被本类的多个用例共享，先清后插，避免上一条用例留下的映射触发唯一键冲突
        jdbcTemplate.update("DELETE FROM desktop_import_map WHERE source_stable_id = ?", stableId);
        jdbcTemplate.update("INSERT INTO desktop_import_map (user_id, entity_type, local_id, source_stable_id, imported_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                1L, entityType, localId, stableId);
    }
}
