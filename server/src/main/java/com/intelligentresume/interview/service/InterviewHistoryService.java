package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewSessionSummaryResponse;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.IntSummaryStatistics;
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
        // 摘要只需要计数和分数；投影避免加载题目、答案和反馈 JSON 等大字段。
        Map<Long, IntSummaryStatistics> scoresBySession = recordRepository
                .findScoresBySessionIdInOrderByCreatedAtAsc(sessions.stream().map(InterviewSession::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(InterviewRecordRepository.ScoreProjection::getSessionId,
                        Collectors.summarizingInt(InterviewRecordRepository.ScoreProjection::getRoundScore)));
        return sessions.stream()
                .map(session -> summary(session, scoresBySession.get(session.getId())))
                .toList();
    }

    private InterviewSessionSummaryResponse summary(InterviewSession session, IntSummaryStatistics scores) {
        int actual = scores == null ? 0 : (int) scores.getCount();
        int totalScore = scores == null ? 0 : (int) Math.round(scores.getAverage());
        return new InterviewSessionSummaryResponse(session.getId(), session.getJobDescriptionId(),
                session.getResumeVersionId(), session.getSourceType(), session.getInterviewMode(),
                session.getExecutionMode(), session.getCompletionReason(), session.getTargetQuestionCount(),
                actual, totalScore, session.getCreatedAt(), session.getUpdatedAt());
    }
}
