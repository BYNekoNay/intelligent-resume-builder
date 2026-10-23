package com.intelligentresume.desktop;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端（H2）JSON 列的**读写两侧**回归门禁。
 *
 * <p>缺陷本体（实测字节形态，不是推测）：H2 的 JSON 列对参数绑定方式敏感 ——
 * <ul>
 *   <li>Hibernate 自己写 → JSON 对象 {@code byte[29]={"a":1}}；</li>
 *   <li>字符串参数（{@code ?} / {@code setString}）→ JSON <b>字符串标量</b>
 *       {@code byte[35]="{\"a\":1}"}（双编码）；</li>
 *   <li>{@code CAST(? AS JSON)} → 仍是字符串标量（实测，别用）；</li>
 *   <li>{@code ? FORMAT JSON} → JSON 对象（与 Hibernate 写入形态一致）。</li>
 * </ul>
 * 双编码行被 Hibernate 读回时抛
 * {@code Could not deserialize string to java type: JsonJavaType(Map)}，于是桌面端 AI 任务链路、
 * 简历版本读取全挂。MySQL 会把文本规范化成对象，所以只有桌面端踩到。
 *
 * <p>本类同时守住两个修法（缺一条都不算修完）：
 * <ol>
 *   <li><b>读侧容错</b>：{@code LenientJsonFormatMapper}（desktop profile 才启用）能读历史双编码行；</li>
 *   <li><b>写侧规范化</b>：{@code DesktopImportService} 对 JSON 列用 {@code ? FORMAT JSON} 绑定，
 *       让新写入的行就是规范对象 —— 这条用「真实 {@code apply} 之后 JPA 能读回 + 物理形态是对象」
 *       钉住，而不是手工造行。</li>
 * </ol>
 *
 * <p><b>反证</b>：非 JSON 文本（{@code not-json}）喂进去必须仍然抛错 —— 证明宽容 mapper 不是
 * 「什么都放过」，否则它会把真实的数据损坏静默吸收掉。
 */
@SpringBootTest
@ActiveProfiles({"desktop", "test"})
class JsonColumnH2ReadbackIT {

    private static final String DATA_DIR = "target/json-readback-it-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String absolute = Path.of(DATA_DIR).toAbsolutePath().toString().replace('\\', '/');
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + absolute + "/resume"
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE");
        registry.add("app.desktop.enabled", () -> "true");
        registry.add("app.desktop.data-dir", () -> absolute);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiTaskRepository taskRepository;

    @Autowired
    private ResumeVersionRepository versionRepository;

    @Autowired
    private JobDescriptionRepository jobRepository;

    @Autowired
    private DesktopImportService importService;

    @Test
    @DisplayName("控制组：Hibernate 自己写的 JSON 行始终可读（修法不得改变正常路径行为）")
    void hibernateWrittenRowStaysReadable() {
        AiTask task = new AiTask();
        task.setUserId(localUserId());
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setIdempotencyKey("readback-jpa");
        task.setRequestFingerprint("readback-jpa");
        task.setInputSnapshotJson(Map.of("taskType", "JOB_GENERATION"));
        task.setStatus(AiTaskStatus.PENDING);
        AiTask saved = taskRepository.saveAndFlush(task);

        AiTask stored = taskRepository.findById(saved.getId()).orElseThrow();
        assertEquals("JOB_GENERATION", stored.getInputSnapshotJson().get("taskType"));
    }

    @Test
    @DisplayName("读侧容错：历史双编码行（字符串参数写入）现在能被实体读回")
    void legacyStringScalarRowIsReadableAfterFix() {
        long userId = localUserId();
        jdbcTemplate.update("INSERT INTO ai_task (user_id, task_type, idempotency_key, request_fingerprint,"
                        + " input_snapshot_json, status, retry_count, created_at, updated_at)"
                        + " VALUES (?, 'JOB_GENERATION', 'readback-legacy', 'readback-legacy',"
                        + " '{\"taskType\":\"JOB_GENERATION\"}', 'PENDING', 0, ?, ?)",
                userId, LocalDateTime.now(), LocalDateTime.now());
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_task WHERE user_id = ?", Long.class, userId);

        Map<String, Object> restored = taskRepository.findById(id).orElseThrow().getInputSnapshotJson();
        assertNotNull(restored, "历史坏行必须能读出来");
        assertEquals("JOB_GENERATION", restored.get("taskType"), "读回来的应是解析后的对象，而不是 JSON 文本");
    }

    @Test
    @DisplayName("写侧规范化：真实 apply 之后，导入的 JSON 列是对象形态且能被实体读回")
    void importedJsonColumnsAreObjectsAndReadable() {
        Map<String, Object> pkg = workspacePackage(901);
        importService.apply(pkg, Map.<String, Object>of());

        Long versionId = localIdOfStableId("web:resumes:1001:versions:1002");
        assertNotNull(versionId, "导入后必须留下简历版本的映射");

        // 物理形态：库里应是 JSON 对象（内含引号未被转义），不是字符串标量
        String raw = rawJson(versionId);
        assertFalse(raw.contains("\\\""), "导入写入的 resume_json 必须是 JSON 对象，实测物理形态：" + raw);
        assertTrue(raw.contains("\"basics\""), "物理内容应是原始 JSON 对象：" + raw);

        // 读回路径：JPA 必须读得出 Map（这正是缺陷原先挂掉的地方）
        ResumeVersion stored = versionRepository.findById(versionId).orElseThrow();
        assertNotNull(stored.getResumeJson());
        assertEquals("张三", nameOf(stored.getResumeJson()), "读回的简历 JSON 内容必须与导入的一致");
    }

    @Test
    @DisplayName("写侧规范化同样覆盖冲突覆盖（USE_IMPORTED 走 UPDATE，不是 INSERT）")
    void useImportedUpdateAlsoWritesJsonObject() {
        importService.apply(workspacePackage(903), Map.<String, Object>of());
        Long localJd = localIdOfStableId("web:jobDescriptions:903");
        assertNotNull(localJd, "导入后必须留下岗位的映射");

        // 同一 stableId、JSON 列内容变了 → 冲突 → USE_IMPORTED 走 UPDATE 的 SET 分支。
        // 注：本夹具只改顶层记录。嵌套集合自批次 1.4 起**也参与预览**（见 DesktopImportServiceIT），
        // 因此这里先断言「除岗位外其余条目都判跳过」，否则「其余条目被误判成冲突」不会让本用例变红。
        Map<String, Object> changed = workspacePackage(903);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobDescriptions = (List<Map<String, Object>>) changed.get("jobDescriptions");
        jobDescriptions.get(0).put("parsedKeywordsJson", "{\"keywords\":[\"spring\"]}");

        DesktopImportService.ImportPreview preview = importService.preview(changed);
        assertEquals(0, preview.added(), "第二次导入不应有新增");
        assertEquals(1, preview.conflicts(), "只有岗位的 JSON 内容变了");
        assertEquals(preview.items().size() - 1, preview.skipped(), "其余条目（简历 + 版本）应判跳过");

        DesktopImportService.ImportResult result = importService.apply(changed,
                Map.<String, Object>of("web:jobDescriptions:903", "USE_IMPORTED"));
        assertEquals(1, result.updated(), "内容变化 + USE_IMPORTED 应覆盖这一条");

        String raw = rawColumn("job_description", "parsed_keywords_json", localJd);
        assertFalse(raw.contains("\\\""), "UPDATE 写入的 parsed_keywords_json 必须是 JSON 对象，实测：" + raw);
        assertTrue(raw.contains("spring"), "物理内容应是本次导入的 JSON：" + raw);

        JobDescription stored = jobRepository.findByIdAndUserId(localJd, localUser()).orElseThrow();
        assertNotNull(stored.getParsedKeywordsJson(), "UPDATE 之后 JSON 列必须能被实体读回");
        assertEquals(List.of("spring"), stored.getParsedKeywordsJson().get("keywords"));
    }

    @Test
    @DisplayName("反证：非 JSON 文本仍然必须抛错（宽容 mapper 不是「什么都放过」）")
    void nonJsonTextStillFails() {
        long userId = localUserId();
        jdbcTemplate.update("INSERT INTO ai_task (user_id, task_type, idempotency_key, request_fingerprint,"
                        + " input_snapshot_json, status, retry_count, created_at, updated_at)"
                        + " VALUES (?, 'JOB_GENERATION', 'readback-bad', 'readback-bad',"
                        + " 'not-json', 'PENDING', 0, ?, ?)",
                userId, LocalDateTime.now(), LocalDateTime.now());
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_task WHERE idempotency_key = ?", Long.class, "readback-bad");

        Exception failure = assertThrows(Exception.class, () -> taskRepository.findById(id),
                "非 JSON 内容必须继续报错，不能被宽容 mapper 静默吞掉");
        assertTrue(rootMessages(failure).contains("无法反序列化") || rootMessages(failure).contains("JsonJavaType"),
                "报错应指向 JSON 反序列化失败，实际：" + rootMessages(failure));
    }

    // ------------------------------------------------------------------ 辅助

    private String rawJson(Long versionId) {
        return rawColumn("resume_version", "resume_json", versionId);
    }

    private String rawColumn(String table, String column, Long id) {
        Object raw = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private String nameOf(Map<String, Object> resumeJson) {
        Object basics = resumeJson.get("basics");
        return basics instanceof Map<?, ?> map ? String.valueOf(((Map<String, Object>) map).get("name")) : null;
    }

    private String rootMessages(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            builder.append(current.getMessage()).append(" | ");
        }
        return builder.toString();
    }

    private long localUserId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM user", Long.class);
        return id == null ? -1L : id;
    }

    private Long localUser() {
        return localUserId();
    }

    private Long localIdOfStableId(String stableId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT local_id FROM desktop_import_map WHERE source_stable_id = ?", Long.class, stableId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 一个最小工作区包：岗位 + 简历（含版本，resumeJson 为字符串）+ 投递。 */
    private Map<String, Object> workspacePackage(int base) {
        Map<String, Object> jobDescription = new LinkedHashMap<>();
        jobDescription.put("id", base);
        jobDescription.put("stableId", "web:jobDescriptions:" + base);
        jobDescription.put("title", "Readback JD " + base);
        jobDescription.put("companyName", "Example Corp");
        jobDescription.put("jdText", "Java and Spring Boot");
        jobDescription.put("parsedKeywordsJson", "{\"keywords\":[\"java\"]}");
        jobDescription.put("createdAt", "2026-01-01T10:00:00");
        jobDescription.put("updatedAt", "2026-01-01T10:00:00");

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", base + 101);
        version.put("stableId", "web:resumes:" + (base + 100) + ":versions:" + (base + 101));
        version.put("versionNo", 1);
        version.put("sourceType", "MANUAL");
        version.put("resumeJson", "{\"basics\":{\"name\":\"张三\"}}");
        version.put("createdBy", 1);
        version.put("createdAt", "2026-01-01T10:00:00");
        version.put("updatedAt", "2026-01-01T10:00:00");

        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("id", base + 100);
        resume.put("stableId", "web:resumes:" + (base + 100));
        resume.put("title", "Readback Resume " + base);
        resume.put("currentVersionId", base + 101);
        resume.put("createdAt", "2026-01-01T10:00:00");
        resume.put("updatedAt", "2026-01-01T10:00:00");
        resume.put("versions", List.of(version));

        Map<String, Object> application = new LinkedHashMap<>();
        application.put("id", base + 200);
        application.put("stableId", "web:applications:" + (base + 200));
        application.put("companyName", "Example Corp");
        application.put("position", "Backend Engineer");
        application.put("status", "APPLIED");
        application.put("jobDescriptionId", base);
        application.put("resumeId", base + 100);
        application.put("resumeVersionId", base + 101);
        application.put("createdAt", "2026-01-01T10:00:00");
        application.put("updatedAt", "2026-01-01T10:00:00");

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("formatVersion", 3);
        pkg.put("source", "web");
        pkg.put("manifest", Map.of("packageId", "web-export-json-readback", "source", "web", "recordCount", 3));
        pkg.put("jobDescriptions", List.of(jobDescription));
        pkg.put("resumes", List.of(resume));
        pkg.put("applications", List.of(application));
        return pkg;
    }
}
