package com.intelligentresume.application.service;

import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.dto.*;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.application.repository.ApplicationStatusHistoryRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ApplicationService {
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(ApplicationStatus.DRAFT, Set.of(ApplicationStatus.DRAFT, ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.APPLIED, Set.of(ApplicationStatus.APPLIED, ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.SCREENING, Set.of(ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.INTERVIEWING, Set.of(ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.OFFERED, Set.of(ApplicationStatus.OFFERED, ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.NO_RESPONSE)),
            Map.entry(ApplicationStatus.HIRED, Set.of(ApplicationStatus.HIRED)),
            Map.entry(ApplicationStatus.REJECTED, Set.of(ApplicationStatus.REJECTED)),
            Map.entry(ApplicationStatus.WITHDRAWN, Set.of(ApplicationStatus.WITHDRAWN)),
            // A no-response outcome can be reopened when the employer responds, closed as
            // withdrawn by the user, or confirmed as a rejection. The most common real-world
            // ending is "no reply, eventually confirmed rejected", so REJECTED must be reachable
            // in one step -- routing it through APPLIED would forge a NO_RESPONSE->APPLIED event
            // in the audit timeline that never happened. REJECTED is terminal: it only self-loops.
            Map.entry(ApplicationStatus.NO_RESPONSE, Set.of(ApplicationStatus.NO_RESPONSE, ApplicationStatus.APPLIED, ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFERED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN)));

    private static final String FOLLOW_UP_ALL = "ALL";
    private static final String FOLLOW_UP_TODAY = "TODAY";
    private static final String FOLLOW_UP_OVERDUE = "OVERDUE";

    private final ApplicationRecordRepository repository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeVersionRepository versionRepository;
    private final ApplicationStatusHistoryRepository historyRepository;

    /** Retained for focused legacy unit tests; Spring uses the history-aware constructor. */
    public ApplicationService(ApplicationRecordRepository repository,
                              JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository) {
        this(repository, jobRepository, versionRepository, null);
    }

    @Autowired
    public ApplicationService(ApplicationRecordRepository repository,
                              JobDescriptionRepository jobRepository,
                              ResumeVersionRepository versionRepository,
                              ApplicationStatusHistoryRepository historyRepository) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.versionRepository = versionRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> list(Long userId, String followUp) {
        String mode = normalizeFollowUp(followUp);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        return repository.findByUserIdAndFollowUp(userId, mode, startOfDay, endOfDay, now)
                .stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationResponse get(Long id, Long userId) {
        ApplicationRecord record = owned(id, userId);
        return response(record, readHistory(record.getId()));
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
        // 修复 P1-4：创建即进入 DRAFT，记录初始状态进入时刻
        LocalDateTime now = LocalDateTime.now();
        record.setStageEnteredAt(now);
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        record.setNextFollowUpAt(request.nextFollowUpAt());
        ApplicationRecord saved = repository.saveAndFlush(record);
        recordHistory(saved.getId(), null, initialStatus, null, now);
        return response(saved, readHistory(saved.getId()));
    }

    @Transactional
    public ApplicationResponse update(Long id, UpdateApplicationRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (request.status() != null && request.status() != record.getStatus()) {
            throw new BusinessException(ErrorCode.CONFLICT, "请通过状态接口更新投递状态");
        }
        boolean lineageChanged = !Objects.equals(request.jobDescriptionId(), record.getJobDescriptionId())
                || !Objects.equals(request.resumeVersionId(), record.getResumeVersionId());
        if (lineageChanged && (record.getAppliedAt() != null || record.getStatus() != ApplicationStatus.DRAFT)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已进入投递流程的岗位和简历版本不可更改");
        }
        // 修复 P1-4：本方法不会改变 status（与当前值不一致时已在上方抛出），
        // 因此严禁刷新 stageEnteredAt —— 正是原缺陷中 updatedAt 被任意编辑重置的反面。
        validateReferences(request.jobDescriptionId(), request.resumeVersionId(), userId);
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setResumeVersionId(request.resumeVersionId());
        record.setCoverLetterText(request.coverLetterText());
        record.setEmailBodyText(request.emailBodyText());
        record.setOpeningMessageText(request.openingMessageText());
        record.setNextFollowUpAt(request.nextFollowUpAt());
        ApplicationRecord saved = repository.saveAndFlush(record);
        return response(saved, readHistory(saved.getId()));
    }

    @Transactional
    public ApplicationResponse updateStatus(Long id, UpdateApplicationStatusRequest request, Long userId) {
        ApplicationRecord record = owned(id, userId);
        requireVersion(record, request.version());
        if (!TRANSITIONS.get(record.getStatus()).contains(request.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "不允许从 " + record.getStatus() + " 迁移到 " + request.status());
        }
        boolean statusChanged = request.status() != record.getStatus();
        LocalDateTime changedAt = LocalDateTime.now();
        ApplicationStatus previousStatus = record.getStatus();
        if (request.status() == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(changedAt);
        }
        if (statusChanged) {
            // 修复 P1-4：仅当状态实际发生迁移时刷新进入时刻；
            // 状态未变化（如 DRAFT→DRAFT、补 feedback）不得刷新，否则停留时长被重置。
            record.setStageEnteredAt(changedAt);
        }
        record.setStatus(request.status());
        // PATCH 语义：feedbackText 缺席或 null = 不改动备注（否则「拖拽改状态」这类不关心备注的
        // 调用会把已有备注清空）；显式空串（或纯空白）= 清空备注。
        if (request.feedbackText() != null) {
            record.setFeedbackText(request.feedbackText().isBlank() ? null : request.feedbackText());
        }
        ApplicationRecord saved = repository.saveAndFlush(record);
        if (statusChanged) {
            recordHistory(saved.getId(), previousStatus, request.status(), request.note(), changedAt);
        }
        return response(saved, readHistory(saved.getId()));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        repository.delete(owned(id, userId));
    }

    @Transactional(readOnly = true)
    public ApplicationStatsResponse stats(Long userId) {
        // 本方法只负责两次仓储调用与事务边界；统计口径与算法见 ApplicationStatsCalculator
        // （纯函数，可单测钉住，也避免本类继续变胖）。
        // #3：计数在 SQL 聚合（group by），只有需要逐行时间戳的三个阶段才读行；
        // 时长聚合在 Java 侧计算，兼容 MySQL 与 H2（不依赖 TIMESTAMPDIFF）。
        return ApplicationStatsCalculator.calculate(
                repository.countGroupByStatus(userId),
                repository.findDurationRowsByUserId(userId),
                LocalDateTime.now());
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
        // 归属校验先于归档判定：不通过时一律 404，避免用归档状态反推他人版本是否存在
        if (!userId.equals(version.getCreatedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        // 归档是可逆状态：与 ATS/评分/导出/沟通/面试一致用 409 + 可操作文案
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再发起投递");
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

    private ApplicationResponse response(ApplicationRecord record, List<ApplicationStatusHistoryResponse> history) {
        return new ApplicationResponse(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getCoverLetterText(), record.getEmailBodyText(), record.getOpeningMessageText(),
                record.getFeedbackText(), record.getAppliedAt(), record.getNextFollowUpAt(), record.getVersion(),
                record.getCreatedAt(), record.getUpdatedAt(), history);
    }

    private List<ApplicationStatusHistoryResponse> readHistory(Long applicationId) {
        if (historyRepository == null) return List.of();
        return historyRepository.findByApplicationRecordIdOrderByChangedAtAscIdAsc(applicationId).stream()
                .map(item -> new ApplicationStatusHistoryResponse(item.getId(), item.getFromStatus(), item.getToStatus(),
                        item.getNote(), item.getChangedAt()))
                .toList();
    }

    private void recordHistory(Long applicationId, ApplicationStatus from, ApplicationStatus to,
                               String note, LocalDateTime changedAt) {
        if (historyRepository == null) return;
        com.intelligentresume.application.domain.ApplicationStatusHistory history =
                new com.intelligentresume.application.domain.ApplicationStatusHistory();
        history.setApplicationRecordId(applicationId);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setNote(note == null || note.isBlank() ? null : note);
        history.setChangedAt(changedAt);
        historyRepository.save(history);
    }

    /** #50：列表摘要——草稿长文本不进列表（按需从详情接口拉取），用 draftCount 支撑「n/3」标记。 */
    private ApplicationSummary summary(ApplicationRecord record) {
        return new ApplicationSummary(record.getId(), record.getJobDescriptionId(), record.getResumeVersionId(),
                record.getStatus(), record.getFeedbackText(), draftCount(record), record.getAppliedAt(),
                record.getNextFollowUpAt(), record.getVersion(), record.getCreatedAt(), record.getUpdatedAt());
    }

    private int draftCount(ApplicationRecord record) {
        int count = 0;
        if (present(record.getCoverLetterText())) count++;
        if (present(record.getEmailBodyText())) count++;
        if (present(record.getOpeningMessageText())) count++;
        return count;
    }

    private boolean present(String value) {
        return value != null && !value.isEmpty();
    }
}
