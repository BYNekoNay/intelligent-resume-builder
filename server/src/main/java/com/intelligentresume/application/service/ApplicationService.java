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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
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

    private static final String FOLLOW_UP_ALL = "ALL";
    private static final String FOLLOW_UP_TODAY = "TODAY";
    private static final String FOLLOW_UP_OVERDUE = "OVERDUE";

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
    public List<ApplicationResponse> list(Long userId, String followUp) {
        String mode = normalizeFollowUp(followUp);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        return repository.findByUserIdAndFollowUp(userId, mode, startOfDay, endOfDay, now)
                .stream().map(this::response).toList();
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
        record.setNextFollowUpAt(request.nextFollowUpAt());
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
        record.setNextFollowUpAt(request.nextFollowUpAt());
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

    @Transactional(readOnly = true)
    public ApplicationStatsResponse stats(Long userId) {
        List<ApplicationRecord> records = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        int total = records.size();

        // byStatus：各状态数量 + count/total*100（保留 1 位小数）
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status, 0L);
        }
        for (ApplicationRecord record : records) {
            counts.merge(record.getStatus(), 1L, Long::sum);
        }
        List<ApplicationStatsResponse.StatusCount> byStatus = new ArrayList<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            long count = counts.get(status);
            Double percent = total == 0 ? null : Math.round(count * 1000.0 / total) / 10.0;
            byStatus.add(new ApplicationStatsResponse.StatusCount(status, count, percent));
        }

        // 转化率：排除 REJECTED/WITHDRAWN 对分母的干扰
        long appliedOrFurther = countIn(records, ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED);
        long interviewingOrFurther = countIn(records, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED);
        long offered = counts.get(ApplicationStatus.OFFERED);
        Double appliedToInterviewing = ratio(countIn(records, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED), appliedOrFurther);
        Double interviewingToOffered = ratio(offered, interviewingOrFurther);
        Double appliedToOffered = ratio(offered, appliedOrFurther);

        // 平均停留（近似）：分母 0 → null
        Double appliedDuration = avgAppliedDuration(records, LocalDateTime.now());
        Double interviewingDuration = avgInterviewingDuration(records, LocalDateTime.now());
        Double totalToOffer = avgTotalToOffer(records);

        return new ApplicationStatsResponse(total, byStatus,
                new ApplicationStatsResponse.ConversionRates(appliedToInterviewing, interviewingToOffered, appliedToOffered),
                new ApplicationStatsResponse.StageDurations(appliedDuration, interviewingDuration, totalToOffer));
    }

    private long countIn(List<ApplicationRecord> records, ApplicationStatus... statuses) {
        Set<ApplicationStatus> set = Set.of(statuses);
        return records.stream().filter(record -> set.contains(record.getStatus())).count();
    }

    private Double ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return Math.round(numerator * 1000.0 / denominator) / 1000.0;
    }

    private Double avgAppliedDuration(List<ApplicationRecord> records, LocalDateTime now) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.APPLIED)
                .map(record -> {
                    LocalDateTime base = record.getAppliedAt() != null ? record.getAppliedAt() : record.getCreatedAt();
                    return base == null ? null : ChronoUnit.MINUTES.between(base, now) / 1440.0;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }

    private Double avgInterviewingDuration(List<ApplicationRecord> records, LocalDateTime now) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.INTERVIEWING)
                .map(record -> record.getUpdatedAt() == null ? null
                        : ChronoUnit.MINUTES.between(record.getUpdatedAt(), now) / 1440.0)
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }

    private Double avgTotalToOffer(List<ApplicationRecord> records) {
        List<Double> days = records.stream()
                .filter(record -> record.getStatus() == ApplicationStatus.OFFERED)
                .map(record -> {
                    LocalDateTime base = record.getAppliedAt() != null ? record.getAppliedAt() : record.getCreatedAt();
                    if (base == null || record.getUpdatedAt() == null) return null;
                    return ChronoUnit.MINUTES.between(base, record.getUpdatedAt()) / 1440.0;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return days.isEmpty() ? null : Math.round(days.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10.0) / 10.0;
    }

    private String normalizeFollowUp(String followUp) {
        if (followUp == null || followUp.isBlank()) return FOLLOW_UP_ALL;
        String upper = followUp.trim().toUpperCase();
        if (FOLLOW_UP_TODAY.equals(upper) || FOLLOW_UP_OVERDUE.equals(upper)) return upper;
        return FOLLOW_UP_ALL;
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
                record.getFeedbackText(), record.getAppliedAt(), record.getNextFollowUpAt(), record.getVersion(),
                record.getCreatedAt(), record.getUpdatedAt());
    }
}
