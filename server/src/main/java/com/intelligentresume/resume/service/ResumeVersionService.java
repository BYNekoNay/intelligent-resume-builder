package com.intelligentresume.resume.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.RestoreResumeVersionRequest;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历版本的生命周期管理：保存快照、列历史、恢复为新版本、归档/取消归档，以及 ATS 溯源上下文的落库。
 *
 * <p>要点（改这个类之前先看）：
 * <ul>
 *     <li>版本号 = MAX(version_no) + 1，并发访问由数据库唯一约束 {@code uk_resume_version_no} 兜底；
 *         冲突统一转成 409（{@code CONFLICT}），调用方可重试。</li>
 *     <li>历史版本不可修改：本类不提供 update 接口，"内容改错"只能靠 {@link #restore} 复制成新版本。</li>
 *     <li>所有写路径先 {@code findOwnedForUpdate} 拿行锁再动数据；要改 {@code current_version_id} 的，
 *         必须在这把锁内改，否则陈旧实体会把并发推进的版本指针覆盖回去。</li>
 *     <li>{@code templateCode} 是写入时归一化的派生列：新建版本、历史行惰性回填都必须走
 *         {@link ResumeTemplateCodes#normalize}，否则列表展示的模板口径会分叉。</li>
 *     <li>「当前版本不能归档」「归档版本不能设为当前」（后者在 ResumeService 中）是互斥的两条红线。</li>
 * </ul>
 */
@Service
public class ResumeVersionService {

    /** 客户端提交的 ATS 条目引用，格式固定为 {@code evidence:<下标>} 或 {@code action:<下标>}。 */
    private static final Pattern ATS_ITEM_PATTERN = Pattern.compile("^(evidence|action):(0|[1-9]\\d*)$");
    /** ATS 建议里的章节名别名 → 平台章节键（大小写与分隔符由 {@link #mapAtsSection} 先归一）。 */
    private static final Map<String, String> ATS_SECTION_ALIASES = Map.ofEntries(
            Map.entry("basics", "basics"), Map.entry("summary", "basics"), Map.entry("profile", "basics"),
            Map.entry("personal summary", "basics"), Map.entry("professional summary", "basics"),
            Map.entry("个人信息", "basics"), Map.entry("个人概要", "basics"),
            Map.entry("objective", "objective"), Map.entry("career objective", "objective"), Map.entry("target role", "objective"),
            Map.entry("求职目标", "objective"), Map.entry("links", "links"), Map.entry("profiles", "links"), Map.entry("个人链接", "links"),
            Map.entry("work", "work"), Map.entry("experience", "work"), Map.entry("work experience", "work"),
            Map.entry("professional experience", "work"), Map.entry("employment", "work"), Map.entry("工作经历", "work"), Map.entry("工作经验", "work"),
            Map.entry("volunteering", "volunteering"), Map.entry("volunteer", "volunteering"), Map.entry("volunteer experience", "volunteering"), Map.entry("志愿经历", "volunteering"),
            Map.entry("skills", "skills"), Map.entry("skill", "skills"), Map.entry("technical skills", "skills"), Map.entry("技能", "skills"), Map.entry("专业技能", "skills"),
            Map.entry("projects", "projects"), Map.entry("project", "projects"), Map.entry("project experience", "projects"), Map.entry("项目经历", "projects"),
            Map.entry("education", "education"), Map.entry("academic", "education"), Map.entry("教育背景", "education"),
            Map.entry("courses", "courses"), Map.entry("course", "courses"), Map.entry("training", "courses"), Map.entry("课程培训", "courses"),
            Map.entry("certificates", "certificates"), Map.entry("certificate", "certificates"), Map.entry("certifications", "certificates"), Map.entry("专业证书", "certificates"),
            Map.entry("publications", "publications"), Map.entry("publication", "publications"), Map.entry("research", "publications"), Map.entry("研究成果", "publications"),
            Map.entry("awards", "awards"), Map.entry("award", "awards"), Map.entry("honors", "awards"), Map.entry("奖项荣誉", "awards"),
            Map.entry("languages", "languages"), Map.entry("language", "languages"), Map.entry("语言能力", "languages"),
            Map.entry("customsections", "customSections"), Map.entry("custom sections", "customSections"), Map.entry("自定义模块", "customSections"));

    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JsonResumeValidator jsonResumeValidator;
    private final AtsCheckResultRepository atsCheckResultRepository;

    public ResumeVersionService(ResumeVersionRepository versionRepository,
                                ResumeRepository resumeRepository,
                                JsonResumeValidator jsonResumeValidator,
                                AtsCheckResultRepository atsCheckResultRepository) {
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jsonResumeValidator = jsonResumeValidator;
        this.atsCheckResultRepository = atsCheckResultRepository;
    }

    /**
     * 保存一次新版本。写库前依次做三件事：校验简历归属（顺带加行锁）、校验 JSON Resume 结构、取下一个版本号。
     *
     * <p>副作用：简历的第一个版本会被自动设为 {@code currentVersionId}，之后的一律不动当前指针——
     * 保存不等于切换当前版本，这是两条独立操作。
     */
    @Transactional
    public ResumeVersionDetail save(Long resumeId, SaveVersionRequest req, Long userId) {
        // 校验简历归属
        Resume resume = findOwnedForUpdate(resumeId, userId);

        // 校验 JSON Resume 结构
        jsonResumeValidator.validate(req.resumeJson());

        // 原子获取下一个版本号
        Integer maxNo = versionRepository.findMaxVersionNoByResumeId(resumeId);
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;

        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resumeId);
        version.setVersionNo(nextNo);
        version.setSourceType(req.sourceType());
        version.setResumeJson(req.resumeJson());
        // #50：派生列在写入时即归一化，列表投影无需再读 resume_json。
        version.setTemplateCode(templateCode(req.resumeJson()));
        version.setOptimizationSummary(req.optimizationSummary());
        version.setCreatedBy(userId);

        try {
            versionRepository.save(version);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }

        // 如果是第一个版本，自动设为当前版本
        if (resume.getCurrentVersionId() == null) {
            resume.setCurrentVersionId(version.getId());
            resumeRepository.save(resume);
        }

        return toDetail(version);
    }

    /**
     * 按简历列版本历史。{@code archived=true} 只返回已归档（{@code deleted_at} 非空）的行。
     *
     * <p>注意本方法虽然只读业务数据，但**不是** {@code readOnly} 事务：历史行的 {@code template_code}
     * 可能为 NULL，需要在同一事务里惰性回填，见 {@link #backfillTemplateCodes}。
     */
    @Transactional
    public List<ResumeVersionSummary> listByResume(Long resumeId, boolean archived, Long userId) {
        // 校验简历归属
        resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        // #50：摘要投影不加载 resume_json（单版本上限 256KB）与 generation_context。
        // 本方法非只读：首次读到 V32 之前的历史行时会惰性回填 template_code（幂等）。
        List<ResumeVersionRepository.VersionSummaryProjection> rows = archived
                ? versionRepository.findArchivedSummariesByResumeId(resumeId)
                : versionRepository.findActiveSummariesByResumeId(resumeId);
        Map<Long, String> backfilled = backfillTemplateCodes(rows);
        return rows.stream()
                .map(row -> new ResumeVersionSummary(
                        row.getId(), row.getVersionNo(), row.getSourceType(),
                        backfilled.getOrDefault(row.getId(), row.getTemplateCode()),
                        row.getOptimizationSummary(), row.getCreatedAt(), row.getDeletedAt(),
                        row.getRestoredFromVersionId()))
                .toList();
    }

    /**
     * template_code 为 V32 新增派生列：历史行为 NULL，首次被列表读到时就地派生并写回（幂等）。
     *
     * <p>不在迁移里 SQL 回填的原因：归一化白名单属应用层语义
     * （{@code ResumeTemplateCodes.normalize}，含大小写与未知取值兜底），SQL 无法安全复刻。
     */
    private Map<Long, String> backfillTemplateCodes(List<ResumeVersionRepository.VersionSummaryProjection> rows) {
        List<Long> missing = rows.stream()
                .filter(row -> row.getTemplateCode() == null)
                .map(ResumeVersionRepository.VersionSummaryProjection::getId)
                .toList();
        if (missing.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> derived = new HashMap<>();
        for (ResumeVersion version : versionRepository.findAllById(missing)) {
            String code = templateCode(version.getResumeJson());
            version.setTemplateCode(code); // 事务提交时由脏检查写回
            derived.put(version.getId(), code);
        }
        return derived;
    }

    /**
     * 查看单个版本详情（含完整 {@code resume_json} 与生成上下文，因此不用于列表）。
     *
     * <p>权限校验走"版本 → 简历 → userId"两跳：先按 id 取版本，再用其 {@code resumeId} 校验归属；
     * 任一环节不存在都返回 404，不区分"没有"和"不是你的"。
     */
    @Transactional(readOnly = true)
    public ResumeVersionDetail get(Long versionId, Long userId) {
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));

        // 通过简历归属校验用户权限
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        return toDetail(version);
    }

    /**
     * 恢复：把指定历史版本**复制**成一条新的 {@code RESTORED} 版本，并设为当前版本；源版本保持不变。
     *
     * <p>{@code request} 可空。非空时表示这次恢复是从某条 ATS 建议发起的，需要校验 ATS 结果归属、
     * 完成态、条目下标与章节可编辑性，并把溯源信息写进新版本的 {@code generationContext}。
     * 因此 request 传了但内容不合法会抛 400/404/409，恢复不会发生。
     */
    @Transactional
    public ResumeVersionDetail restore(Long resumeId, Long versionId,
                                       RestoreResumeVersionRequest request, Long userId) {
        Resume resume = findOwnedForUpdate(resumeId, userId);
        ResumeVersion source = findVersionForResume(versionId, resumeId);
        jsonResumeValidator.validate(source.getResumeJson());
        Map<String, Object> generationContext = request == null ? null : atsGenerationContext(request, source, userId);

        ResumeVersion restored = createVersion(resumeId, ResumeSourceType.RESTORED,
                source.getResumeJson(), "恢复自 v" + source.getVersionNo(), generationContext, userId);
        restored.setRestoredFromVersionId(source.getId());
        versionRepository.save(restored);

        resume.setCurrentVersionId(restored.getId());
        resumeRepository.save(resume);
        return toDetail(restored);
    }

    /**
     * 归档（软删除）：只置 {@code deleted_at}，内容与版本号保留，之后可 {@link #unarchive} 还原。
     *
     * <p>幂等：已归档的行不会重复写时间戳。当前版本不允许归档，否则简历会指向一条已隐藏的版本。
     */
    @Transactional
    public void archive(Long resumeId, Long versionId, Long userId) {
        Resume resume = findOwnedForUpdate(resumeId, userId);
        ResumeVersion version = findVersionForResume(versionId, resumeId);
        if (versionId.equals(resume.getCurrentVersionId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前版本不能归档");
        }
        if (version.getDeletedAt() == null) {
            version.setDeletedAt(LocalDateTime.now());
            versionRepository.save(version);
        }
    }

    /**
     * 取消归档：清空 {@code deleted_at}，版本重新出现在活跃列表中。幂等，未归档的行不会被写库。
     *
     * <p>这里只看归属不加「不能是当前版本」的约束——归档期间当前指针不可能指向它。
     */
    @Transactional
    public void unarchive(Long resumeId, Long versionId, Long userId) {
        findOwnedForUpdate(resumeId, userId);
        ResumeVersion version = findVersionForResume(versionId, resumeId);
        if (version.getDeletedAt() != null) {
            version.setDeletedAt(null);
            versionRepository.save(version);
        }
    }

    /**
     * 跨模块事务性创建版本（T08）。由 DraftCommitService 在同一事务中调用。
     *
     * @param resumeId          简历 ID
     * @param sourceType        来源类型
     * @param resumeJson        标准化后的简历 JSON
     * @param summary           优化摘要
     * @param generationContext 生成上下文
     * @param userId            创建者 ID
     * @return 已持久化的 ResumeVersion（含 ID 和 versionNo）
     */
    @Transactional
    public ResumeVersion createInTransaction(Long resumeId, ResumeSourceType sourceType,
                                              Map<String, Object> resumeJson, String summary,
                                              Map<String, Object> generationContext, Long userId) {
        Resume resume = findOwnedForUpdate(resumeId, userId);
        ResumeVersion version = createVersion(resumeId, sourceType, resumeJson, summary, generationContext, userId);

        // 如果是第一个版本，自动设为当前版本
        if (resume.getCurrentVersionId() == null) {
            resume.setCurrentVersionId(version.getId());
            resumeRepository.save(resume);
        }

        return version;
    }

    // ---- helpers ----

    private Resume findOwned(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
    }

    /**
     * 带行锁的归属查询（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>回退到无锁查询是为了兼容不支持的场景（如单元测试里的 repository 替身）：
     * 查得到就继续，查不到仍按 404 处理，不会因为"锁不存在"而误报。
     */
    private Resume findOwnedForUpdate(Long resumeId, Long userId) {
        return resumeRepository.findByIdAndUserIdForUpdate(resumeId, userId)
                .orElseGet(() -> findOwned(resumeId, userId));
    }

    private ResumeVersion findVersionForResume(Long versionId, Long resumeId) {
        return versionRepository.findByIdAndResumeId(versionId, resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));
    }

    /**
     * 组装并持久化一条版本行（不校验 JSON、不改当前指针，由调用方负责）。
     *
     * <p>版本号是"先查 MAX 再 +1"的乐观做法，唯一约束才是并发下的最终裁判；
     * 冲突时抛 409 让调用方重试，不能吞掉重试（否则会丢版本）。
     */
    private ResumeVersion createVersion(Long resumeId, ResumeSourceType sourceType,
                                        Map<String, Object> resumeJson, String summary,
                                        Map<String, Object> generationContext, Long userId) {
        Integer maxNo = versionRepository.findMaxVersionNoByResumeId(resumeId);
        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resumeId);
        version.setVersionNo((maxNo == null ? 0 : maxNo) + 1);
        version.setSourceType(sourceType);
        version.setResumeJson(resumeJson);
        // #50：派生列在写入时即归一化，列表投影无需再读 resume_json。
        version.setTemplateCode(templateCode(resumeJson));
        version.setOptimizationSummary(summary);
        version.setGenerationContext(generationContext);
        version.setCreatedBy(userId);
        try {
            return versionRepository.save(version);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }
    }

    /**
     * 从 {@code resume_json.template.code} 派生模板代号。
     *
     * <p>三种输入都必须安全：{@code resumeJson} 为 null、{@code template} 不是对象、
     * {@code code} 不是字符串或不在支持列表——一律落到默认模板，绝不抛异常（否则老数据读列表就 500）。
     */
    private String templateCode(Map<String, Object> resumeJson) {
        Object template = resumeJson == null ? null : resumeJson.get("template");
        Object code = template instanceof Map<?, ?> values ? values.get("code") : null;
        return ResumeTemplateCodes.normalize(code);
    }

    private ResumeVersionDetail toDetail(ResumeVersion v) {
        return new ResumeVersionDetail(v.getId(), v.getResumeId(), v.getVersionNo(), v.getSourceType(),
                v.getResumeJson(), v.getOptimizationSummary(), v.getGenerationContext(),
                v.getCreatedAt(), v.getDeletedAt(), v.getRestoredFromVersionId());
    }

    /**
     * 把一次 ATS 建议转成可审计的 {@code generationContext}（写入新版本的 {@code atsProvenance}）。
     *
     * <p>五道校验顺序不能调换，且缺一不可：结果归属 → 结果必须属于被恢复的源版本 → 分析必须 COMPLETED
     * → 条目下标必须落在 {@code evidenceFindings}/{@code prioritizedActions} 范围内 → 章节必须能映射到可编辑章节。
     * 任何一步不过都直接抛异常，不写半成品溯源（宁可失败，也不留一条解释不了的来源记录）。
     *
     * <p>ATS 文案里的 {@code section} 用的是自由写法（中英文、连字符、大小写），由 {@link #mapAtsSection}
     * 归一；映射表没有的章节一律视为不可编辑。
     */
    private Map<String, Object> atsGenerationContext(RestoreResumeVersionRequest request,
                                                      ResumeVersion source, Long userId) {
        AtsCheckResult result = atsCheckResultRepository.findByIdAndUserId(request.atsResultId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ATS analysis not found"));
        if (!source.getId().equals(result.getResumeVersionId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS analysis source version does not match");
        }

        Map<String, Object> resultJson = result.getResultJson();
        if (!"COMPLETED".equals(resultJson == null ? null : resultJson.get("analysisStatus"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "ATS analysis is not complete");
        }

        AtsItemReference item = parseAtsItem(request.atsItem());
        Map<String, Object> insights = object(resultJson.get("aiInsights"), "ATS insights are unavailable");
        String listKey = "evidence".equals(item.kind()) ? "evidenceFindings" : "prioritizedActions";
        List<?> items = list(insights.get(listKey), "ATS item is unavailable");
        if (item.index() >= items.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS item is outside the available range");
        }
        Map<String, Object> selected = object(items.get(item.index()), "ATS item is invalid");
        String mappedSection = mapAtsSection(text(selected.get("section")));
        if (mappedSection == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS section is not editable");
        }
        String objectiveKey = "evidence".equals(item.kind()) ? "suggestion" : "action";
        String objective = text(selected.get(objectiveKey));
        if (objective == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS objective is unavailable");
        }

        return Map.of("atsProvenance", Map.of(
                "resultId", result.getId(),
                "sourceVersionId", source.getId(),
                "jobDescriptionId", result.getJobDescriptionId(),
                "mappedSection", mappedSection,
                "itemKind", item.kind(),
                "itemIndex", item.index(),
                "optimizationObjective", objective));
    }

    private AtsItemReference parseAtsItem(String value) {
        Matcher matcher = ATS_ITEM_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS item is invalid");
        }
        try {
            return new AtsItemReference(matcher.group(1), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "ATS item is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value, String errorMessage) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
    }

    private List<?> list(Object value, String errorMessage) {
        if (value instanceof List<?> list) return list;
        throw new BusinessException(ErrorCode.VALIDATION, errorMessage);
    }

    private String text(Object value) {
        if (!(value instanceof String string)) return null;
        String trimmed = string.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String mapAtsSection(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase().replaceAll("[_-]+", " ").replaceAll("\\s+", " ").trim();
        return ATS_SECTION_ALIASES.get(normalized);
    }

    private record AtsItemReference(String kind, int index) {
    }
}
