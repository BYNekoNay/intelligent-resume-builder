package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewReportResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 面试结束与报告聚合（单事务）。
 *
 * <p>finish / report 使用方法级 @Transactional（原样迁移），不混用 TransactionTemplate。
 */
@Service
public class InterviewReportService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewStateAssembler stateAssembler;

    public InterviewReportService(InterviewSessionRepository sessionRepository,
                                  InterviewRecordRepository recordRepository,
                                  InterviewStateAssembler stateAssembler) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.stateAssembler = stateAssembler;
    }

    @Transactional
    public InterviewStateResponse finish(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        if (session.getStatus() == InterviewStatus.COMPLETED) {
            return stateAssembler.buildStateResponse(session, null, null);
        }
        if (session.getStatus() == InterviewStatus.GENERATING_QUESTION
                || session.getStatus() == InterviewStatus.EVALUATING_ANSWER) {
            throw new BusinessException(ErrorCode.CONFLICT, "Cannot finish while an AI operation is in progress");
        }

        long completedCount = recordRepository.countBySessionId(session.getId());
        if (completedCount < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少完成 1 题后才能结束面试");
        }

        session.setStatus(InterviewStatus.COMPLETED);
        session.setCurrentQuestion(null);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }

    @Transactional(readOnly = true)
    public InterviewReportResponse report(Long id, Long userId) {
        InterviewSession session = stateAssembler.owned(id, userId);

        if (session.getStatus() != InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试未完成");
        }

        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());

        if (records.isEmpty()) {
            return new InterviewReportResponse(0, "尚未完成任何回答", List.of(), List.of(), List.of(), List.of(),
                    null, 0, 0, null, null, 0, 0);
        }

        int total = (int) Math.round(records.stream().mapToInt(InterviewRecord::getRoundScore).average().orElse(0));

        // 五维聚合
        double sumRel = 0, sumEvid = 0, sumStruct = 0, sumRole = 0, sumAuth = 0;
        int aiCount = 0, ruleCount = 0;
        for (InterviewRecord r : records) {
            Object dims = r.getFeedbackJson().get("dimensionScores");
            if (dims instanceof Map<?, ?> m) {
                sumRel += stateAssembler.num(m.get("relevance"));
                sumEvid += stateAssembler.num(m.get("evidenceSpecificity"));
                sumStruct += stateAssembler.num(m.get("structureClarity"));
                sumRole += stateAssembler.num(m.get("roleCompetency"));
                sumAuth += stateAssembler.num(m.get("authenticityReflection"));
            }
            if (r.getEvaluationSource() == EvaluationSource.RULE) ruleCount++;
            else aiCount++;
        }
        int n = records.size();
        int dRel = (int) Math.round(sumRel / n);
        int dEvid = (int) Math.round(sumEvid / n);
        int dStruct = (int) Math.round(sumStruct / n);
        int dRole = (int) Math.round(sumRole / n);
        int dAuth = (int) Math.round(sumAuth / n);

        InterviewReportResponse.DimensionScores dimensionScores =
                new InterviewReportResponse.DimensionScores(dRel, dEvid, dStruct, dRole, dAuth);

        // 报告来源类型
        EvaluationSource reportSource;
        if (aiCount > 0 && ruleCount > 0) reportSource = EvaluationSource.MIXED;
        else if (aiCount > 0) reportSource = EvaluationSource.AI;
        else reportSource = EvaluationSource.RULE;

        return new InterviewReportResponse(
                total, "已完成 %d 轮回答，平均得分 %d 分。面试已结束。".formatted(n, total),
                stateAssembler.distinctFeedback(records, "strengths"),
                stateAssembler.distinctFeedback(records, "improvements"),
                stateAssembler.distinctFeedback(records, "resumeSuggestions"),
                stateAssembler.distinctFeedback(records, "expressionSuggestions"),
                dimensionScores,
                session.getTargetQuestionCount(), n,
                session.getCompletionReason(), reportSource,
                aiCount, ruleCount
        );
    }
}
