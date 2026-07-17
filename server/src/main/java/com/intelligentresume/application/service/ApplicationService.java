package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.dto.ApplicationCreateRequest;
import com.intelligentresume.application.dto.ApplicationResponse;
import com.intelligentresume.application.dto.ApplicationStatusRequest;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationService {
    private final ApplicationRecordRepository repository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;

    public ApplicationService(ApplicationRecordRepository repository, JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository, ResumeRepository resumeRepository) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
    }

    @Transactional
    public ApplicationResponse create(ApplicationCreateRequest request, Long userId) {
        validateOwnership(request.jobDescriptionId(), request.resumeVersionId(), userId);
        ApplicationRecord record = new ApplicationRecord();
        record.setUserId(userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setStatus(request.status());
        record.setCoverLetterText(request.coverLetterText());
        record.setOpeningMessageText(request.openingMessageText());
        if (request.status() != ApplicationRecord.Status.DRAFT) record.setAppliedAt(LocalDateTime.now());
        return toResponse(repository.save(record));
    }

    public List<ApplicationResponse> list(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    public ApplicationResponse get(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId).map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public ApplicationResponse update(Long id, ApplicationCreateRequest request, Long userId) {
        ApplicationRecord record = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        validateOwnership(request.jobDescriptionId(), request.resumeVersionId(), userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setStatus(request.status());
        record.setCoverLetterText(request.coverLetterText());
        record.setOpeningMessageText(request.openingMessageText());
        if (record.getAppliedAt() == null && request.status() != ApplicationRecord.Status.DRAFT) {
            record.setAppliedAt(LocalDateTime.now());
        }
        return toResponse(repository.save(record));
    }

    @Transactional
    public ApplicationResponse updateStatus(Long id, ApplicationStatusRequest request, Long userId) {
        ApplicationRecord record = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        record.setStatus(request.status());
        record.setFeedbackText(request.feedbackText());
        if (record.getAppliedAt() == null && request.status() != ApplicationRecord.Status.DRAFT) {
            record.setAppliedAt(LocalDateTime.now());
        }
        return toResponse(repository.save(record));
    }

    private void validateOwnership(Long jobId, Long versionId, Long userId) {
        jobRepository.findByIdAndUserId(jobId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ResumeVersion version = versionRepository.findById(versionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ApplicationResponse toResponse(ApplicationRecord record) {
        return new ApplicationResponse(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getCoverLetterText(), record.getOpeningMessageText(), record.getFeedbackText(),
                record.getAppliedAt(), record.getCreatedAt(), record.getUpdatedAt());
    }
}
