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

/**
 * 历史面试会话列表。服务端聚合 actualQuestionCount / totalScore（个人数据量级小）。
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
        return sessionRepository.findCompletedByUserId(userId, InterviewStatus.COMPLETED, jobDescriptionId)
                .stream().map(this::summary).toList();
    }

    private InterviewSessionSummaryResponse summary(InterviewSession session) {
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        int actual = records.size();
        int totalScore = actual == 0 ? 0
                : (int) Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));
        return new InterviewSessionSummaryResponse(session.getId(), session.getJobDescriptionId(),
                session.getResumeVersionId(), session.getSourceType(), session.getInterviewMode(),
                session.getExecutionMode(), session.getCompletionReason(), session.getTargetQuestionCount(),
                actual, totalScore, session.getCreatedAt(), session.getUpdatedAt());
    }
}
