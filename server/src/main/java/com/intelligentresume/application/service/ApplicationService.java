package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.dto.*;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ApplicationService {
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = Map.of(
            ApplicationStatus.DRAFT, Set.of(ApplicationStatus.DRAFT, ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.APPLIED, Set.of(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.INTERVIEWING, Set.of(ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
            ApplicationStatus.OFFERED, Set.of(ApplicationStatus.OFFERED),
            ApplicationStatus.REJECTED, Set.of(ApplicationStatus.REJECTED),
            ApplicationStatus.WITHDRAWN, Set.of(ApplicationStatus.WITHDRAWN));

    private final ApplicationRecordRepository repository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeVersionRepository versionRepository;

    public ApplicationService(ApplicationRecordRepository repository,
                              JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> list(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::response).toList();
    }

    @Transactional
    public ApplicationResponse create(CreateApplicationRequest request, Long userId) {
        validateReferences(request.jobDescriptionId(), request.resumeVersionId(), userId);
        ApplicationStatus initialStatus = request.status() == null ? ApplicationStatus.DRAFT : request.status();
        if (initialStatus != ApplicationStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递记录必须从 DRAFT 状态创建");
        }
        ApplicationRecord record = new ApplicationRecord();
        record.setUserId(userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setStatus(initialStatus);
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        return response(repository.saveAndFlush(record));
    }

    @Transactional
    public ApplicationResponse update(Long id, UpdateApplicationRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (request.status() != null && request.status() != record.getStatus()) {
            throw new BusinessException(ErrorCode.CONFLICT, "请通过状态接口更新投递状态");
        }
        validateReferences(request.jobDescriptionId(), request.resumeVersionId(), userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        return response(repository.saveAndFlush(record));
    }

    @Transactional
    public ApplicationResponse updateStatus(Long id, UpdateApplicationStatusRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (!TRANSITIONS.get(record.getStatus()).contains(request.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "不允许从 " + record.getStatus() + " 迁移到 " + request.status());
        }
        if (request.status() == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(LocalDateTime.now());
        }
        record.setStatus(request.status());
        record.setFeedbackText(request.feedbackText());
        return response(repository.saveAndFlush(record));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        repository.delete(owned(id, userId));
    }

    private void validateReferences(Long jobId, Long versionId, Long userId) {
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
        ResumeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (!userId.equals(version.getCreatedBy()) || version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
    }

    private ApplicationRecord owned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "投递记录不存在"));
    }

    private void requireVersion(ApplicationRecord record, Long expected) {
        if (!expected.equals(record.getVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "投递记录已被更新，请刷新后重试");
        }
    }

    private ApplicationResponse response(ApplicationRecord record) {
        return new ApplicationResponse(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getCoverLetterText(), record.getEmailBodyText(), record.getOpeningMessageText(),
                record.getFeedbackText(), record.getAppliedAt(), record.getVersion(), record.getCreatedAt(), record.getUpdatedAt());
    }
}
