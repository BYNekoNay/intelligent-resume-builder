package com.intelligentresume.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.domain.ApplicationStatusHistory;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.application.repository.ApplicationStatusHistoryRepository;
import com.intelligentresume.auth.service.AccountExportService;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **真实导出包 → 桌面导入**的往返门禁（ADR-012 阶段四的真实用户路径）。
 *
 * <p>为什么要单独立一个类：现有的 {@code DesktopImportServiceIT} 与 {@code JsonColumnH2ReadbackIT}
 * 都是**手写**导入包（JSON 列写成字符串 {@code "{\"basics\":...}"}），因此它们只证明
 * 「手写字符串包能导入」。而真实链路是这样走的：
 * <ol>
 *   <li>Web 侧 {@code AccountExportService} 用 {@code objectMapper.valueToTree(entity)} 序列化实体
 *       → 包内 JSON 列是**嵌套对象**，不是字符串；</li>
 *   <li>包落成 {@code .json} 文件；</li>
 *   <li>桌面端把文件读成 {@code Map<String,Object>} → JSON 列在内存里是 {@code Map}。</li>
 * </ol>
 * 于是「真实包」与「测试包」在**参数类型**上是两回事：{@code ImportColumnBinder.placeholder()}
 * 只对 {@code String} 返回 {@code ? FORMAT JSON}，{@code Map} 会走普通 {@code ?} 并被
 * {@code setObject} 绑成字符串标量（H2 双编码）。本类就是钉这条差异。
 *
 * <p>同时钉住另一个已确认的缺陷：{@code DesktopImportService} 的
 * {@code SUPPORTED_ORDER}（3 类）与 {@code SUPPORTED_TABLES}（4 类）互相矛盾，
 * {@code careerMaterials} 既不预览、也不写入、还不算「不支持」→ **静默丢弃**。
 *
 * <p>因此本类必须**穿过真实导出链路**构造包（序列化 → 反序列化），不得手工伪造 Map 形态。
 */
@SpringBootTest
@ActiveProfiles({"desktop", "test"})
class DesktopExportImportRoundTripIT {

    private static final String DATA_DIR = "target/round-trip-it-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String absolute = Path.of(DATA_DIR).toAbsolutePath().toString().replace('\\', '/');
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + absolute + "/resume"
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,KEY,VALUE,ORDER,DATABASE");
        registry.add("app.desktop.enabled", () -> "true");
        registry.add("app.desktop.data-dir", () -> absolute);
    }

    @Autowired
    private AccountExportService exportService;

    @Autowired
    private DesktopImportService importService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobDescriptionRepository jobRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ResumeVersionRepository versionRepository;

    @Autowired
    private CareerMaterialRepository materialRepository;

    @Autowired
    private ApplicationRecordRepository applicationRepository;

    @Autowired
    private ApplicationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("真实导出包往返：职业资料不丢、JSON 列读回是对象、且不是被「不支持集合」挡下")
    void realExportPackageSurvivesRoundTrip() throws Exception {
        long userId = localUserId();
        Seeded seed = seedWorkspace(userId);

        Map<String, Object> packageOnDisk = exportThenReadBack(userId);

        DesktopImportService.ImportPreview preview = importService.preview(packageOnDisk);
        assertTrue(preview.unsupportedCollections().isEmpty(),
                "真实导出包不该被判为含不支持集合，否则本用例会被「整份拒绝」假通过："
                        + preview.unsupportedCollections());

        // 缺陷 1（P1）：careerMaterials 必须参与预览。当前 SUPPORTED_ORDER 里没有它 → 这里是空的。
        List<String> previewedCollections = preview.items().stream()
                .map(DesktopImportService.PreviewItem::collection)
                .distinct()
                .toList();
        assertTrue(previewedCollections.contains("careerMaterials"),
                "职业资料必须参与预览，否则等于静默丢弃。实际参与预览的集合：" + previewedCollections);

        importService.apply(packageOnDisk, Map.<String, Object>of());

        // 期望 A：职业资料真的落地，且 content_json 读回是 Map（不是 JSON 文本）
        Long materialLocalId = localIdOfStableId("web:careerMaterials:" + seed.materialId());
        assertNotNull(materialLocalId, "职业资料没有落地 —— 真实迁移会丢掉用户整个资料库");
        CareerMaterial importedMaterial = materialRepository.findById(materialLocalId).orElseThrow();
        assertNotNull(importedMaterial.getContentJson(), "content_json 必须能被实体读回（Map）");
        assertEquals("Spring Boot", firstSkillOf(importedMaterial.getContentJson()),
                "读回的 content_json 内容必须与导出时一致");
        assertNotDoubleEncoded("career_material", "content_json", materialLocalId);

        // 期望 B：简历版本的 resume_json 同样必须是对象形态且可读回
        String versionStableId = "web:resumes:" + seed.resumeId() + ":versions:" + seed.versionId();
        Long versionLocalId = localIdOfStableId(versionStableId);
        assertNotNull(versionLocalId, "简历版本没有落地");
        ResumeVersion importedVersion = versionRepository.findById(versionLocalId).orElseThrow();
        assertNotNull(importedVersion.getResumeJson(), "resume_json 必须能被实体读回（Map）");
        assertEquals("Round Trip", basicsNameOf(importedVersion.getResumeJson()),
                "读回的 resume_json 内容必须与导出时一致");
        assertNotDoubleEncoded("resume_version", "resume_json", versionLocalId);

        // 期望 C：岗位的 parsed_keywords_json 也是对象形态
        Long jobLocalId = localIdOfStableId("web:jobDescriptions:" + seed.jobId());
        assertNotNull(jobLocalId, "岗位没有落地");
        assertNotDoubleEncoded("job_description", "parsed_keywords_json", jobLocalId);
    }

    // ------------------------------------------------------------------ 真实链路

    /**
     * 走一遍用户真实路径：导出 payload → 序列化成文件内容 → 读回成 Map。
     *
     * <p>不能省掉序列化这一步：{@code loadExportPayload} 返回的 Map 里，
     * {@code resumes}/{@code applications} 是 {@code ObjectNode}、其余集合是**实体对象**，
     * 只有过了 JSON 文件这一关才会变成 {@code Map} —— 那才是桌面端真正拿到的形态。
     */
    private Map<String, Object> exportThenReadBack(Long userId) throws Exception {
        Map<String, Object> payload = exportService.loadExportPayload(userId);
        String json = objectMapper.writeValueAsString(payload);
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /** 造一份含全部四类核心集合的数据；JSON 列用嵌套结构（与真实实体一致，不是字符串）。 */
    private Seeded seedWorkspace(long userId) {
        JobDescription job = new JobDescription();
        job.setUserId(userId);
        job.setTitle("Round Trip JD");
        job.setCompanyName("Example Corp");
        job.setJdText("Java and Spring Boot");
        job.setParsedKeywordsJson(Map.of("keywords", List.of("java")));
        job.setParsedAt(LocalDateTime.now());
        Long jobId = jobRepository.saveAndFlush(job).getId();

        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle("Round Trip Resume");
        Long resumeId = resumeRepository.saveAndFlush(resume).getId();

        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resumeId);
        version.setVersionNo(1);
        version.setSourceType(ResumeSourceType.MANUAL);
        version.setResumeJson(Map.of("basics", Map.of("name", "Round Trip")));
        version.setCreatedBy(userId);
        Long versionId = versionRepository.saveAndFlush(version).getId();

        resume.setCurrentVersionId(versionId);
        resumeRepository.saveAndFlush(resume);

        CareerMaterial material = new CareerMaterial();
        material.setUserId(userId);
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setTitle("Round Trip Material");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("skills", List.of("Spring Boot", "MySQL"));
        content.put("summary", Map.of("role", "Backend"));
        material.setContentJson(content);
        Long materialId = materialRepository.saveAndFlush(material).getId();

        ApplicationRecord application = new ApplicationRecord();
        application.setUserId(userId);
        application.setJobDescriptionId(jobId);
        application.setResumeVersionId(versionId);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setAppliedAt(LocalDateTime.now());
        Long applicationId = applicationRepository.saveAndFlush(application).getId();

        ApplicationStatusHistory history = new ApplicationStatusHistory();
        history.setApplicationRecordId(applicationId);
        history.setToStatus(ApplicationStatus.APPLIED);
        history.setNote("round trip");
        history.setChangedAt(LocalDateTime.now());
        statusHistoryRepository.saveAndFlush(history);

        return new Seeded(jobId, resumeId, versionId, materialId);
    }

    private record Seeded(Long jobId, Long resumeId, Long versionId, Long materialId) {
    }

    // ------------------------------------------------------------------ 辅助

    /** 物理形态断言：JSON 列里若出现 {@code \"} 说明被存成了字符串标量（双编码）。 */
    private void assertNotDoubleEncoded(String table, String column, Long id) {
        Object raw = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
        String text = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(raw);
        assertFalse(text.contains("\\\""),
                table + "." + column + " 被写成了 JSON 字符串标量（双编码），实测物理形态：" + text);
    }

    @SuppressWarnings("unchecked")
    private String firstSkillOf(Map<String, Object> contentJson) {
        Object skills = contentJson.get("skills");
        return skills instanceof List<?> list && !list.isEmpty() ? String.valueOf(list.get(0)) : null;
    }

    @SuppressWarnings("unchecked")
    private String basicsNameOf(Map<String, Object> resumeJson) {
        Object basics = resumeJson.get("basics");
        return basics instanceof Map<?, ?> map ? String.valueOf(((Map<String, Object>) map).get("name")) : null;
    }

    private long localUserId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM user", Long.class);
        return id == null ? -1L : id;
    }

    private Long localIdOfStableId(String stableId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT local_id FROM desktop_import_map WHERE source_stable_id = ?", Long.class, stableId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
