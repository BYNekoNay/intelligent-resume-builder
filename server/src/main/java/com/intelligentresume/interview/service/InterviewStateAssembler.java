package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试响应只读组装（无事务边界，由调用方在事务上下文中执行）。
 *
 * <p>负责 buildStateResponse、LastEvaluation、AiFailureInfo、
 * 失败 attempt 查询与反馈去重等纯组装逻辑。
 */
@Component
public class InterviewStateAssembler {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;

    public InterviewStateAssembler(InterviewSessionRepository sessionRepository,
                                   InterviewRecordRepository recordRepository,
                                   InterviewAiAttemptRepository attemptRepository) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
    }

    public InterviewStateResponse buildStateResponse(InterviewSession session,
                                                     InterviewStateResponse.LastEvaluation lastEval,
                                                     InterviewStateResponse.AiFailureInfo aiFailure) {
        long count = recordRepository.countBySessionId(session.getId());
        Integer currentQNo = count > 0 ? (int) count + 1 : null;

        // 自动加载最近一轮评估
        if (lastEval == null && count > 0) {
            List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
            if (!records.isEmpty()) {
                lastEval = buildLastEvaluation(records.get(records.size() - 1));
            }
        }

        return new InterviewStateResponse(
                session.getId(), session.getStatus(), session.getExecutionMode(),
                session.getCurrentQuestion(), currentQNo,
                (int) count, session.getTargetQuestionCount(),
                session.getMinQuestionCount(), session.getMaxQuestionCount(),
                lastEval, aiFailure, session.getCompletionReason()
        );
    }

    @SuppressWarnings("unchecked")
    public InterviewStateResponse.LastEvaluation buildLastEvaluation(InterviewRecord record) {
        InterviewStateResponse.LastEvaluation eval = new InterviewStateResponse.LastEvaluation();
        eval.setRecordId(record.getId());
        eval.setRoundNo(record.getRoundNo());
        eval.setQuestionText(record.getQuestionText());
        eval.setAnswerText(record.getAnswerText());
        eval.setRoundScore(record.getRoundScore());
        eval.setEvaluationSource(record.getEvaluationSource());

        Map<String, Object> feedback = record.getFeedbackJson();
        if (feedback != null) {
            Object dims = feedback.get("dimensionScores");
            if (dims instanceof Map<?, ?> m) {
                InterviewStateResponse.DimensionScores ds = new InterviewStateResponse.DimensionScores();
                ds.setRelevance(num(m.get("relevance")));
                ds.setEvidenceSpecificity(num(m.get("evidenceSpecificity")));
                ds.setStructureClarity(num(m.get("structureClarity")));
                ds.setRoleCompetency(num(m.get("roleCompetency")));
                ds.setAuthenticityReflection(num(m.get("authenticityReflection")));
                eval.setDimensionScores(ds);
            }
            Object strengths = feedback.get("strengths");
            if (strengths instanceof List<?> list) {
                eval.setStrengths(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
            }
            Object improvements = feedback.get("improvements");
            if (improvements instanceof List<?> list) {
                eval.setImprovements(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
            }
            Object suggested = feedback.get("suggestedAnswer");
            if (suggested instanceof String s) {
                eval.setSuggestedAnswer(s);
            }
        }
        return eval;
    }

    public InterviewStateResponse.AiFailureInfo buildAiFailure(InterviewAiAttempt attempt) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(attempt.getId());
        info.setStage(attempt.getOperationType().name());
        info.setRetryable(Boolean.TRUE.equals(attempt.getRetryable()));
        info.setReauthorizationRequired("FORBIDDEN".equals(attempt.getErrorCode()));
        info.setMessageCode(attempt.getErrorCode() != null ? attempt.getErrorCode() : ErrorCode.AI_FAILURE.name());
        return info;
    }

    public InterviewStateResponse.AiFailureInfo buildAiFailure(Long operationId, String stage, BusinessException e) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(operationId);
        info.setStage(stage);
        info.setRetryable(e.getErrorCode() != ErrorCode.FORBIDDEN);
        info.setReauthorizationRequired(e.getErrorCode() == ErrorCode.FORBIDDEN);
        info.setMessageCode(e.getErrorCode().name());
        return info;
    }

    public Optional<InterviewAiAttempt> latestFailedAttempt(Long sessionId) {
        return attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDesc(
                sessionId, com.intelligentresume.interview.domain.AiAttemptStatus.FAILED);
    }

    @SuppressWarnings("unchecked")
    public List<String> distinctFeedback(List<InterviewRecord> records, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (InterviewRecord record : records) {
            Object raw = record.getFeedbackJson().get(key);
            if (raw instanceof Collection<?> items) {
                items.stream().filter(String.class::isInstance).map(String.class::cast).forEach(values::add);
            }
        }
        return values.stream().limit(5).toList();
    }

    public int num(Object val) {
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    public InterviewSession owned(Long id, Long userId) {
        return sessionRepository.findByIdAndUserId(id, userId).orElseThrow(() -> notFound("面试会话不存在"));
    }

    public BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
