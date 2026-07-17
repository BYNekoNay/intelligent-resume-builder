package com.intelligentresume.resume.service;

import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeResponse;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionResponse;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.resume.validation.JsonResumeValidator;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历与版本领域服务。
 *
 * <p>权限:所有读写都按 userId 过滤,跨用户通过 {@link ErrorCode#NOT_FOUND} 兜底。
 * 版本号:每个 resume 的 version_no 单调递增,利用 {@code uk_resume_version_no} 唯一约束兜底并发。
 */
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
    public ResumeResponse create(ResumeCreateRequest request, Long userId) {
        jsonResumeValidator.validate(request.resumeJson());
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(request.title());
        Resume saved = resumeRepository.save(resume);
        // 创建空基线版本的占位:简化 MVP,首版本由用户自行创建
        return toResponse(saved);
    }

    public List<ResumeResponse> list(Long userId) {
        return resumeRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ResumeResponse get(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(resume);
    }

    @Transactional
    public ResumeResponse updateTitle(Long resumeId, String title, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resume.setTitle(title);
        return toResponse(resumeRepository.save(resume));
    }

    @Transactional
    public void softDelete(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resume.setDeletedAt(LocalDateTime.now());
        resumeRepository.save(resume);
    }

    @Transactional
    public ResumeVersionResponse createVersion(Long resumeId, ResumeVersionCreateRequest request, Long userId) {
        jsonResumeValidator.validate(request.resumeJson());
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // 版本号 = max + 1(MVP 串行,UNIQUE 兜底并发)
        int nextVersionNo = resumeVersionRepository.findFirstByResumeIdOrderByVersionNoDesc(resumeId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);

        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resume.getId());
        version.setVersionNo(nextVersionNo);
        version.setSourceType(request.sourceType());
        version.setResumeJson(request.resumeJson());
        version.setOptimizationSummary(request.optimizationSummary());
        version.setCreatedBy(userId);
        ResumeVersion saved = resumeVersionRepository.save(version);

        resume.setCurrentVersionId(saved.getId());
        resumeRepository.save(resume);

        return toResponse(saved);
    }

    public List<ResumeVersionResponse> listVersions(Long resumeId, Long userId) {
        resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return resumeVersionRepository.findByResumeIdOrderByVersionNoDesc(resumeId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ResumeVersionResponse getVersion(Long resumeId, Long versionId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ResumeVersion v = resumeVersionRepository.findByIdAndResumeId(versionId, resume.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(v);
    }

    @Transactional
    public ResumeResponse setCurrentVersion(Long resumeId, Long versionId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ResumeVersion v = resumeVersionRepository.findByIdAndResumeId(versionId, resume.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!v.getResumeId().equals(resume.getId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        resume.setCurrentVersionId(v.getId());
        return toResponse(resumeRepository.save(resume));
    }

    private ResumeResponse toResponse(Resume resume) {
        return new ResumeResponse(resume.getId(), resume.getTitle(), resume.getCurrentVersionId(),
                resume.getCreatedAt(), resume.getUpdatedAt());
    }

    private ResumeVersionResponse toResponse(ResumeVersion v) {
        return new ResumeVersionResponse(v.getId(), v.getResumeId(), v.getVersionNo(),
                v.getSourceType(), v.getResumeJson(), v.getOptimizationSummary(), v.getCreatedAt());
    }
}
