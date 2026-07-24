package com.intelligentresume.resume.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.*;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历 CRUD + 当前版本切换。
 *
 * <p>关键约定：
 * <ul>
 *     <li>所有查询带 userId 条件，杜绝跨用户访问。</li>
 *     <li>软删设置 deleted_at，版本不删除以保留历史快照。</li>
 * </ul>
 */
import com.intelligentresume.resume.domain.ResumeSourceType;
import java.util.Map;

@Service
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JsonResumeValidator jsonResumeValidator;

    public ResumeService(ResumeRepository resumeRepository,
                         ResumeVersionRepository resumeVersionRepository,
                         JsonResumeValidator jsonResumeValidator) {
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jsonResumeValidator = jsonResumeValidator;
    }

    @Transactional
    public ResumeDetail create(CreateResumeRequest req, Long userId) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(req.title());
        resumeRepository.save(resume);

        if (req.resumeJson() != null && !req.resumeJson().isEmpty()) {
            jsonResumeValidator.validate(req.resumeJson());
            int versionNo = 1;
            ResumeVersion version = new ResumeVersion();
            version.setResumeId(resume.getId());
            version.setVersionNo(versionNo);
            version.setSourceType(ResumeSourceType.MANUAL);
            version.setResumeJson(req.resumeJson());
            version.setCreatedBy(userId);
            resumeVersionRepository.save(version);
            resume.setCurrentVersionId(version.getId());
            resumeRepository.save(resume);
        }

        return toDetail(resume);
    }

    @Transactional(readOnly = true)
    public List<ResumeSummary> list(Long userId) {
        return resumeRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查询某 JD 关联的岗位简历。用于"同 JD 再次生成"时判断新建/更新。
     */
    @Transactional(readOnly = true)
    public List<ResumeSummary> listByJobDescription(Long jdId, Long userId) {
        return resumeRepository.findByUserIdAndJobDescriptionIdAndDeletedAtIsNull(userId, jdId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResumeDetail get(Long id, Long userId) {
        Resume resume = findOwned(id, userId);
        return toDetail(resume);
    }

    @Transactional
    public ResumeDetail update(Long id, UpdateResumeRequest req, Long userId) {
        Resume resume = findOwned(id, userId);
        if (req.title() != null && !req.title().isBlank()) {
            resume.setTitle(req.title());
        }
        resumeRepository.save(resume);
        return toDetail(resume);
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        Resume resume = findOwned(id, userId);
        resume.setDeletedAt(LocalDateTime.now());
        resumeRepository.save(resume);
    }

    @Transactional
    public void setCurrentVersion(Long resumeId, Long versionId, Long userId) {
        Resume resume = findOwned(resumeId, userId);
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));
        if (!version.getResumeId().equals(resumeId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本不属于该简历");
        }
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "归档版本不能设为当前版本");
        }
        resume.setCurrentVersionId(versionId);
        resumeRepository.save(resume);
    }

    // ---- helpers ----

    private Resume findOwned(Long id, Long userId) {
        return resumeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
    }

    private ResumeSummary toSummary(Resume r) {
        return new ResumeSummary(r.getId(), r.getTitle(), r.getCurrentVersionId(),
                r.getJobDescriptionId(), r.getUpdatedAt());
    }

    private ResumeDetail toDetail(Resume r) {
        return new ResumeDetail(r.getId(), r.getTitle(), r.getCurrentVersionId(), r.getJobDescriptionId(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
