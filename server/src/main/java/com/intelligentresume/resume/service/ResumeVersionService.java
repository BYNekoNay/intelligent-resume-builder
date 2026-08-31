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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历版本管理：保存新版本、列出版本历史、查看版本详情。
 *
 * <p>关键约定：
 * <ul>
 *     <li>版本号 = MAX(version_no) + 1，由数据库唯一约束 {@code uk_resume_version_no} 保障并发安全。</li>
 *     <li>历史版本不可修改（本卡不提供 update 接口）。</li>
 *     <li>保存版本前校验 JSON Resume 结构。</li>
 * </ul>
 */
@Service
public class ResumeVersionService {

    private static final Pattern ATS_ITEM_PATTERN = Pattern.compile("^(evidence|action):(0|[1-9]\\d*)$");
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

    @Transactional
    public ResumeVersionDetail save(Long resumeId, SaveVersionRequest req, Long userId) {
        // 校验简历归属
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

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

    @Transactional(readOnly = true)
    public List<ResumeVersionSummary> listByResume(Long resumeId, boolean archived, Long userId) {
        // 校验简历归属
        resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        List<ResumeVersion> versions = archived
                ? versionRepository.findByResumeIdAndDeletedAtIsNotNullOrderByVersionNoDesc(resumeId)
                : versionRepository.findByResumeIdAndDeletedAtIsNullOrderByVersionNoDesc(resumeId);
        return versions
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResumeVersionDetail get(Long versionId, Long userId) {
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));

        // 通过简历归属校验用户权限
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        return toDetail(version);
    }

    @Transactional
    public ResumeVersionDetail restore(Long resumeId, Long versionId, Long userId) {
        Resume resume = findOwned(resumeId, userId);
        ResumeVersion source = findVersionForResume(versionId, resumeId);
        jsonResumeValidator.validate(source.getResumeJson());

        ResumeVersion restored = createVersion(resumeId, ResumeSourceType.RESTORED,
                source.getResumeJson(), "恢复自 v" + source.getVersionNo(), null, userId);
        restored.setRestoredFromVersionId(source.getId());
        versionRepository.save(restored);

        resume.setCurrentVersionId(restored.getId());
        resumeRepository.save(resume);
        return toDetail(restored);
    }

    @Transactional
    public ResumeVersionDetail restore(Long resumeId, Long versionId,
                                       RestoreResumeVersionRequest request, Long userId) {
        Resume resume = findOwned(resumeId, userId);
        ResumeVersion source = findVersionForResume(versionId, resumeId);
        jsonResumeValidator.validate(source.getResumeJson());
        Map<String, Object> generationContext = request == null ? null : atsGenerationContext(request, source, userId);

        ResumeVersion restored = createVersion(resumeId, ResumeSourceType.RESTORED,
                source.getResumeJson(), "Restored from v" + source.getVersionNo(), generationContext, userId);
        restored.setRestoredFromVersionId(source.getId());
        versionRepository.save(restored);

        resume.setCurrentVersionId(restored.getId());
        resumeRepository.save(resume);
        return toDetail(restored);
    }

    @Transactional
    public void archive(Long resumeId, Long versionId, Long userId) {
        Resume resume = findOwned(resumeId, userId);
        ResumeVersion version = findVersionForResume(versionId, resumeId);
        if (versionId.equals(resume.getCurrentVersionId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前版本不能归档");
        }
        if (version.getDeletedAt() == null) {
            version.setDeletedAt(LocalDateTime.now());
            versionRepository.save(version);
        }
    }

    @Transactional
    public void unarchive(Long resumeId, Long versionId, Long userId) {
        findOwned(resumeId, userId);
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
        ResumeVersion version = createVersion(resumeId, sourceType, resumeJson, summary, generationContext, userId);

        // 如果是第一个版本，自动设为当前版本
        Resume resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume != null && resume.getCurrentVersionId() == null) {
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

    private ResumeVersion findVersionForResume(Long versionId, Long resumeId) {
        return versionRepository.findByIdAndResumeId(versionId, resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));
    }

    private ResumeVersion createVersion(Long resumeId, ResumeSourceType sourceType,
                                        Map<String, Object> resumeJson, String summary,
                                        Map<String, Object> generationContext, Long userId) {
        Integer maxNo = versionRepository.findMaxVersionNoByResumeId(resumeId);
        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resumeId);
        version.setVersionNo((maxNo == null ? 0 : maxNo) + 1);
        version.setSourceType(sourceType);
        version.setResumeJson(resumeJson);
        version.setOptimizationSummary(summary);
        version.setGenerationContext(generationContext);
        version.setCreatedBy(userId);
        try {
            return versionRepository.save(version);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }
    }

    private ResumeVersionSummary toSummary(ResumeVersion v) {
        return new ResumeVersionSummary(v.getId(), v.getVersionNo(), v.getSourceType(),
                templateCode(v.getResumeJson()), v.getOptimizationSummary(), v.getGenerationContext(),
                v.getCreatedAt(), v.getDeletedAt(), v.getRestoredFromVersionId());
    }

    private String templateCode(Map<String, Object> resumeJson) {
        Object template = resumeJson == null ? null : resumeJson.get("template");
        Object code = template instanceof Map<?, ?> values ? values.get("code") : null;
        return ResumeTemplateCodes.normalize(code);
    }

    private ResumeVersionDetail toDetail(ResumeVersion v) {
        return new ResumeVersionDetail(v.getId(), v.getVersionNo(), v.getSourceType(),
                v.getResumeJson(), v.getOptimizationSummary(), v.getGenerationContext(),
                v.getCreatedAt(), v.getDeletedAt(), v.getRestoredFromVersionId());
    }

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
