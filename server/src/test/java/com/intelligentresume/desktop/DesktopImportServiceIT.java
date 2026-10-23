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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
     *
     * <p>清整表而不是按 stableId 白名单删：白名单是手工维护的第二份清单 ——
     * 每加一个用例就要记得扩它，忘了就会留下跨用例污染（批次 1.4 改用整表清空）。
     */
    @BeforeEach
    void forgetMappingsWrittenByPreviousTests() {
        jdbcTemplate.update("DELETE FROM desktop_import_map");
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
        // 5 = 岗位 + 简历 + 版本 + 投递 + 状态历史：批次 1.4 起嵌套记录逐条计入（口径：计数 == items.size()）
        assertEquals(5, second.skipped(), "重复导入应全部跳过，且嵌套记录也计入");

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

    // ------------------------------------------------------------------ 嵌套集合（批次 1.4）

    @Test
    void nestedCollectionsAppearInPreviewInParentBeforeChildOrder() {
        DesktopImportService.ImportPreview preview = importService.preview(fullPackage(505));

        List<String> collections = preview.items().stream()
                .map(DesktopImportService.PreviewItem::collection).toList();
        assertTrue(collections.contains("resumes.versions"),
                "简历版本必须参与预览（旧实现完全不下钻），实际：" + collections);
        assertTrue(collections.contains("applications.statusHistory"),
                "投递状态历史必须参与预览，实际：" + collections);
        assertEquals(5, preview.items().size(), "岗位 + 简历 + 版本 + 投递 + 状态历史 = 5 条");
        assertEquals(5, preview.added(), "计数必须计入嵌套记录");
        assertEquals(preview.items().size(), preview.added() + preview.skipped() + preview.conflicts(),
                "三个计数之和必须等于 items.size()");

        assertTrue(collections.indexOf("resumes") < collections.indexOf("resumes.versions"),
                "父记录必须排在子记录之前（items 顺序即写入顺序）");
        assertEquals("resume_version",
                preview.items().get(collections.indexOf("resumes.versions")).entityType());
        assertEquals("application_status_history",
                preview.items().get(collections.indexOf("applications.statusHistory")).entityType());
    }

    @Test
    void childRecordIsWrittenEvenWhenParentIsSkipped() {
        importService.apply(fullPackage(506), Map.<String, Object>of());
        Long parentLocal = localIdOfStableId("web:resumes:606");
        assertNotNull(parentLocal, "首次导入后必须有简历映射");

        // 父记录内容一字不改，只在包内多带一条版本
        Map<String, Object> second = fullPackage(506);
        Map<String, Object> extra = new LinkedHashMap<>(versionsOf(second).get(0));
        extra.put("id", 1606);
        extra.put("stableId", "web:resumes:606:versions:1606");
        extra.put("versionNo", 2);
        firstResume(second).put("versions", List.of(versionsOf(second).get(0), extra));

        DesktopImportService.ImportPreview preview = importService.preview(second);
        assertEquals(DesktopImportService.Decision.SKIP, findItem(preview, "web:resumes:606").decision(),
                "父记录内容未变应跳过");
        assertEquals(DesktopImportService.Decision.ADD, findItem(preview, "web:resumes:606:versions:1606").decision(),
                "父记录跳过不得导致新版本不参与分类");

        DesktopImportService.ImportResult result = importService.apply(second, Map.<String, Object>of());
        assertEquals(1, result.inserted(), "只有新增的那条版本被写入");

        Long newVersion = localIdOfStableId("web:resumes:606:versions:1606");
        assertNotNull(newVersion, "父记录跳过时子记录仍必须写入 —— 旧实现只在父 ADD 分支写子，等于丢数据");
        assertEquals(parentLocal, longOf("SELECT resume_id FROM resume_version WHERE id = ?", newVersion),
                "子记录外键必须指向本地父记录，而不是包里的 Web 主键");
    }

    @Test
    void versionNumberCollisionIsSkippedNotFatal() {
        importService.apply(fullPackage(507), Map.<String, Object>of());
        Long parentLocal = localIdOfStableId("web:resumes:607");

        // 父内容不变；新版本换了 stableId，但 versionNo=1 与本地已有版本撞 uk_resume_version_no
        Map<String, Object> second = fullPackage(507);
        Map<String, Object> collision = new LinkedHashMap<>(versionsOf(second).get(0));
        collision.put("id", 1707);
        collision.put("stableId", "web:resumes:607:versions:1707");
        collision.put("versionNo", 1);
        firstResume(second).put("versions", List.of(collision));

        DesktopImportService.PreviewItem item = findItem(importService.preview(second),
                "web:resumes:607:versions:1707");
        assertEquals(DesktopImportService.Decision.SKIP, item.decision(),
                "同编号版本必须降级为跳过，否则 INSERT 撞唯一键会让整批回滚");
        assertTrue(item.reason().contains("同编号"), "reason 要点明原因，实际：" + item.reason());

        DesktopImportService.ImportResult result = importService.apply(second, Map.<String, Object>of());
        assertEquals(0, result.inserted());
        assertEquals(1, countOf("SELECT COUNT(*) FROM resume_version WHERE resume_id = ?", parentLocal),
                "撞号不得改动本地版本行");
        assertNull(localIdOfStableId("web:resumes:607:versions:1707"), "跳过的版本不得写映射");
    }

    @Test
    void currentVersionIdFollowsThePackageNotTheLastInsertedVersion() {
        Map<String, Object> pkg = fullPackage(508);
        Map<String, Object> v1 = versionsOf(pkg).get(0);
        Map<String, Object> v2 = new LinkedHashMap<>(v1);
        v2.put("id", 1618);
        v2.put("stableId", "web:resumes:608:versions:1618");
        v2.put("versionNo", 2);
        firstResume(pkg).put("versions", List.of(v1, v2));
        assertEquals(609, firstResume(pkg).get("currentVersionId"), "包里的当前版本仍是 v1");

        importService.apply(pkg, Map.<String, Object>of());

        Long resumeLocal = localIdOfStableId("web:resumes:608");
        Long v1Local = localIdOfStableId("web:resumes:608:versions:609");
        Long v2Local = localIdOfStableId("web:resumes:608:versions:1618");
        assertEquals(2, countOf("SELECT COUNT(*) FROM resume_version WHERE resume_id = ?", resumeLocal));
        assertEquals(v1Local, longOf("SELECT current_version_id FROM resume WHERE id = ?", resumeLocal),
                "当前版本指针必须等于包里的 currentVersionId，而不是「最后插入的那条」");
        assertNotEquals(v2Local, longOf("SELECT current_version_id FROM resume WHERE id = ?", resumeLocal));
    }

    @Test
    void createdByIsRemappedToTheLocalUser() {
        Map<String, Object> pkg = fullPackage(509);
        versionsOf(pkg).get(0).put("createdBy", 987654);   // 桌面工作区里不存在的 Web 用户

        importService.apply(pkg, Map.<String, Object>of());

        Long versionLocal = localIdOfStableId("web:resumes:609:versions:610");
        assertNotNull(versionLocal, "createdBy 未重映射会让 FK 失败，整批回滚");
        assertEquals(longOf("SELECT MIN(id) FROM user"),
                longOf("SELECT created_by FROM resume_version WHERE id = ?", versionLocal),
                "版本的 created_by 必须是本地用户，否则真实跨用户导入必然违反 fk_resume_version_user");
    }

    @Test
    void restoredFromVersionIdIsRemappedWithinPackage() {
        Map<String, Object> pkg = fullPackage(510);
        Map<String, Object> v2 = new LinkedHashMap<>(versionsOf(pkg).get(0));
        v2.put("id", 712);
        v2.put("stableId", "web:resumes:610:versions:712");
        v2.put("versionNo", 2);
        v2.put("restoredFromVersionId", 611);   // v1 的 Web 主键
        firstResume(pkg).put("versions", List.of(versionsOf(pkg).get(0), v2));

        importService.apply(pkg, Map.<String, Object>of());

        Long v1Local = localIdOfStableId("web:resumes:610:versions:611");
        Long v2Local = localIdOfStableId("web:resumes:610:versions:712");
        assertNotNull(v2Local);
        assertEquals(v1Local, longOf("SELECT restored_from_version_id FROM resume_version WHERE id = ?", v2Local),
                "包内可解析的还原来源必须映射成本地版本 id");
    }

    @Test
    void restoredFromVersionIdMissingFromPackageBecomesNull() {
        Map<String, Object> pkg = fullPackage(511);
        versionsOf(pkg).get(0).put("restoredFromVersionId", 999999);   // 包内不存在

        importService.apply(pkg, Map.<String, Object>of());

        Long versionLocal = localIdOfStableId("web:resumes:611:versions:612");
        assertNotNull(versionLocal);
        assertNull(longOf("SELECT restored_from_version_id FROM resume_version WHERE id = ?", versionLocal),
                "解析不到的还原来源必须置空：保留 Web id 会 FK 失败，命中本地无关行则产生错误血缘");
    }

    @Test
    void resumeJobDescriptionIdIsRemapped() {
        Map<String, Object> pkg = fullPackage(512);
        firstResume(pkg).put("jobDescriptionId", 512);   // 包内岗位的 Web 主键

        importService.apply(pkg, Map.<String, Object>of());

        Long resumeLocal = localIdOfStableId("web:resumes:612");
        Long jdLocal = localIdOfStableId("web:jobDescriptions:512");
        assertNotNull(resumeLocal);
        assertNotNull(jdLocal);
        assertEquals(jdLocal, longOf("SELECT job_description_id FROM resume WHERE id = ?", resumeLocal),
                "岗位定制简历的 job_description_id 必须映射成本地岗位（V12 外键）");
    }

    @Test
    void danglingMappingIsRecreatedInsteadOfSkipped() {
        // 用状态历史做样本：它只被 application_record 引用、自身不引用别人，
        // 删行不会撞外键，能真实复现「行没了但映射还在」（留存清扫会这样）。
        Map<String, Object> pkg = fullPackage(513);
        importService.apply(pkg, Map.<String, Object>of());
        String stableId = "web:applications:713:statusHistory:3";
        Long historyLocal = localIdOfStableId(stableId);
        assertNotNull(historyLocal);
        jdbcTemplate.update("DELETE FROM application_status_history WHERE id = ?", historyLocal);

        assertEquals(DesktopImportService.Decision.ADD,
                findItem(importService.preview(pkg), stableId).decision(),
                "映射还在但本地行已不存在时必须按新增处理，否则这条记录永久无法再导入");

        importService.apply(pkg, Map.<String, Object>of());
        Long recreated = localIdOfStableId(stableId);
        assertNotNull(recreated);
        assertNotEquals(historyLocal, recreated, "映射必须被修正到新插入的行");
        assertEquals(1, countOf("SELECT COUNT(*) FROM application_status_history WHERE id = ?", recreated));
    }

    @Test
    void duplicateStableIdInPackageIsRejected() {
        Map<String, Object> jd = jobDescription(77, "Java Backend dup");
        Map<String, Object> pkg = packageOf(jd, new LinkedHashMap<>(jd));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> importService.preview(pkg), "包内稳定 ID 重复时不能静默取第一条");
        assertTrue(failure.getMessage().contains("重复"),
                "报错要点明稳定 ID 重复，实际：" + failure.getMessage());
    }

    @Test
    void parentAndChildCanConflictTogether() {
        importService.apply(fullPackage(514), Map.<String, Object>of());
        Long resumeLocal = localIdOfStableId("web:resumes:614");

        Map<String, Object> changed = fullPackage(514);
        firstResume(changed).put("title", "Backend Resume-614-changed");
        versionsOf(changed).get(0).put("resumeJson", "{\"basics\":{\"name\":\"Li Si\"}}");

        assertEquals(2, importService.preview(changed).conflicts(), "父与子各一条冲突");

        DesktopImportService.ImportResult result = importService.apply(changed, Map.<String, Object>of(
                "web:resumes:614", "USE_IMPORTED",
                "web:resumes:614:versions:615", "USE_IMPORTED"));
        assertEquals(2, result.updated(), "两条 USE_IMPORTED 都应被更新");
        assertEquals("Backend Resume-614-changed",
                stringOf("SELECT title FROM resume WHERE id = ?", resumeLocal));
        assertEquals(1, countOf("SELECT COUNT(*) FROM resume_version WHERE resume_id = ?", resumeLocal),
                "覆盖不得新增版本行");
    }

    @Test
    void childConflictKeepLocalLeavesChildUntouched() {
        importService.apply(fullPackage(515), Map.<String, Object>of());
        Long versionLocal = localIdOfStableId("web:resumes:615:versions:616");
        assertNotNull(versionLocal);
        String before = rawOf("SELECT resume_json FROM resume_version WHERE id = ?", versionLocal);

        Map<String, Object> changed = fullPackage(515);
        versionsOf(changed).get(0).put("resumeJson", "{\"basics\":{\"name\":\"Wang Wu\"}}");

        DesktopImportService.ImportResult result = importService.apply(changed,
                Map.<String, Object>of("web:resumes:615:versions:616", "KEEP_LOCAL"));

        assertEquals(1, result.keptLocal(), "子记录的 KEEP_LOCAL 必须被接受");
        assertEquals(0, result.updated(), "KEEP_LOCAL 不得改动本地版本");
        assertEquals(before, rawOf("SELECT resume_json FROM resume_version WHERE id = ?", versionLocal),
                "本地版本内容必须一字未改");
    }

    // ------------------------------------------------------------------ 包构造

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

    private DesktopImportService.PreviewItem findItem(DesktopImportService.ImportPreview preview, String stableId) {
        return preview.items().stream()
                .filter(item -> item.stableId().equals(stableId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("预览里没有这条记录：" + stableId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstResume(Map<String, Object> pkg) {
        return ((List<Map<String, Object>>) pkg.get("resumes")).get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> versionsOf(Map<String, Object> pkg) {
        return (List<Map<String, Object>>) firstResume(pkg).get("versions");
    }

    private Long longOf(String sql, Object... args) {
        List<Long> values = jdbcTemplate.queryForList(sql, Long.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private int countOf(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String stringOf(String sql, Object... args) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 读原始列值（H2 的 JSON 列可能回成 byte[]，统一按 UTF-8 文本比较）。 */
    private String rawOf(String sql, Object... args) {
        Object value = jdbcTemplate.queryForObject(sql, Object.class, args);
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value == null ? null : String.valueOf(value);
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
