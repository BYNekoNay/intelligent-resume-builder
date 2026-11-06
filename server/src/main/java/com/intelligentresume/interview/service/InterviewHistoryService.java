package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
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
 *
 * <p>【小白导读】
 * ① 业务问题：「我的面试记录」页——列出该用户已完成的每场面试，带实际题数和平均分，
 *    可按 JD 筛选。点进去才看逐轮报告。
 * ② 链路位置：上游是 {@code InterviewService.listHistory}（GET /api/interviews）；
 *    只读会话与记录两张表的元数据列，产出 {@link InterviewSessionSummaryResponse} 列表。
 * ③ 改坏它：列表页加载变慢（把简历原文等大字段全读进内存）、平均分口径和报告页对不上。
 *
 * <p>要点：列表只返回已完成会话；所有计数与均值都由记录聚合得出，
 * 查询走投影（只取元数据列），避免把简历原文、题目、答案等大字段读进内存。
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
        // 传了 JD 筛选时先校验归属：不存在的/他人的 JD 直接 404，
        // 而不是静默返回空列表让调用方分不清「没有面试」和「JD 用错了」
        if (jobDescriptionId != null) {
            jobRepository.findByIdAndUserId(jobDescriptionId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在"));
        }
        // 读模型投影（ideation #50）：会话只取摘要元数据，不加载简历原文/题目等大字段。
        List<InterviewSessionRepository.SessionSummaryProjection> sessions =
                sessionRepository.findCompletedSummariesByUserId(userId, InterviewStatus.COMPLETED, jobDescriptionId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        // 摘要只需要计数和分数；投影避免加载题目、答案和反馈 JSON 等大字段。
        Map<Long, IntSummaryStatistics> scoresBySession = recordRepository
                .findScoresBySessionIdInOrderByRoundNoAscIdAsc(sessions.stream()
                        .map(InterviewSessionRepository.SessionSummaryProjection::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(InterviewRecordRepository.ScoreProjection::getSessionId,
                        Collectors.summarizingInt(InterviewRecordRepository.ScoreProjection::getRoundScore)));
        return sessions.stream()
                .map(session -> summary(session, scoresBySession.get(session.getId())))
                .toList();
    }

    private InterviewSessionSummaryResponse summary(InterviewSessionRepository.SessionSummaryProjection session,
                                                    IntSummaryStatistics scores) {
        // 没有记录时（理论上不该出现，因为只查已完成会话）按 0 处理，避免 NPE
        int actual = scores == null ? 0 : (int) scores.getCount();
        // totalScore 是平均分四舍五入，与报告页口径一致
        int totalScore = scores == null ? 0 : (int) Math.round(scores.getAverage());
        return new InterviewSessionSummaryResponse(session.getId(), session.getJobDescriptionId(),
                session.getResumeVersionId(), session.getSourceType(), session.getInterviewMode(),
                session.getExecutionMode(), session.getCompletionReason(), session.getTargetQuestionCount(),
                actual, totalScore, session.getCreatedAt(), session.getUpdatedAt());
    }
}
