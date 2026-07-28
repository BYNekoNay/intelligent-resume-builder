package com.intelligentresume.resume.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JsonResumeValidator jsonResumeValidator;

    public ResumeVersionService(ResumeVersionRepository versionRepository,
                                ResumeRepository resumeRepository,
                                JsonResumeValidator jsonResumeValidator) {
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jsonResumeValidator = jsonResumeValidator;
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
                templateCode(v.getResumeJson()), v.getOptimizationSummary(), v.getCreatedAt(), v.getDeletedAt(), v.getRestoredFromVersionId());
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
}
