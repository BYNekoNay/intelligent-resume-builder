package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewSessionSummaryResponse;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 历史面试会话列表。服务端聚合 actualQuestionCount / totalScore。
 */
@Service
public class InterviewHistoryService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final JobDescriptionRepository jobRepository;

    public InterviewHistoryService(InterviewSessionRepository sessionRepository,
                                   InterviewRecordRepository recordRepository,
                                   JobDescriptionRepository jobRepository) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(readOnly = true)
    public List<InterviewSessionSummaryResponse> list(Long userId, Long jobDescriptionId) {
        if (jobDescriptionId != null) {
            jobRepository.findByIdAndUserId(jobDescriptionId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在"));
        }
        List<InterviewSession> sessions = sessionRepository.findCompletedByUserId(userId, InterviewStatus.COMPLETED, jobDescriptionId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        // 一次批量加载所有会话的答题记录，按 sessionId 分组，避免 N 个会话 = N 次查询
        Map<Long, List<InterviewRecord>> recordsBySession = recordRepository
                .findBySessionIdInOrderByCreatedAtAsc(sessions.stream().map(InterviewSession::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(InterviewRecord::getSessionId));
        return sessions.stream()
                .map(session -> summary(session, recordsBySession.getOrDefault(session.getId(), List.of())))
                .toList();
    }

    private InterviewSessionSummaryResponse summary(InterviewSession session, List<InterviewRecord> records) {
        int actual = records.size();
        int totalScore = actual == 0 ? 0
                : (int) Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));
        return new InterviewSessionSummaryResponse(session.getId(), session.getJobDescriptionId(),
                session.getResumeVersionId(), session.getSourceType(), session.getInterviewMode(),
                session.getExecutionMode(), session.getCompletionReason(), session.getTargetQuestionCount(),
                actual, totalScore, session.getCreatedAt(), session.getUpdatedAt());
    }
}
