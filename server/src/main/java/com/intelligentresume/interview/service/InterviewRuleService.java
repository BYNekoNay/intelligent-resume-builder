package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 面试规则降级模式（单事务）。
 *
 * <p>continueWithRules / ruleAnswer 使用方法级 @Transactional（原样迁移），
 * 不混用 TransactionTemplate。
 */
@Service
public class InterviewRuleService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final InterviewRuleEngine ruleEngine;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewRuleService(InterviewSessionRepository sessionRepository,
                                InterviewRecordRepository recordRepository,
                                InterviewAiAttemptRepository attemptRepository,
                                InterviewRuleEngine ruleEngine,
                                InterviewStateAssembler stateAssembler,
                                InterviewOperationSupport operationSupport) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
        this.ruleEngine = ruleEngine;
        this.stateAssembler = stateAssembler;
        this.operationSupport = operationSupport;
    }

    @Transactional
    public InterviewStateResponse continueWithRules(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        if (session.getStatus() != InterviewStatus.AI_ACTION_REQUIRED) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许规则降级");
        }

        session.setExecutionMode(ExecutionMode.RULE);
        session.setStatus(InterviewStatus.AWAITING_ANSWER);

        // 标记所有失败的 AI attempt 为 RULE_FALLBACK
        List<InterviewAiAttempt> failedAttempts = attemptRepository.findAllBySessionId(session.getId()).stream()
                .filter(a -> a.getStatus() == AiAttemptStatus.FAILED)
                .toList();
        for (InterviewAiAttempt a : failedAttempts) {
            a.setStatus(AiAttemptStatus.RULE_FALLBACK);
        }
        attemptRepository.saveAll(failedAttempts);

        // 如果当前没有问题（首题失败），给规则首题
        if (session.getCurrentQuestion() == null) {
            session.setCurrentQuestion(InterviewRuleEngine.RULE_FIRST_QUESTION);
        }
        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }

    @Transactional
    public InterviewStateResponse ruleAnswer(Long id, String answerText, Long userId, String idempotencyKey) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

        Optional<InterviewAiAttempt> existing = attemptRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            InterviewAiAttempt previous = existing.get();
            String replayFingerprint = operationSupport.buildFingerprint(id, previous.getRoundNo(), answerText);
            if (!Objects.equals(previous.getSessionId(), id)
                    || previous.getOperationType() != AiAttemptOperationType.ANSWER_EVALUATION
                    || !Objects.equals(previous.getRequestFingerprint(), replayFingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
            }
            if (previous.getStatus() == AiAttemptStatus.RULE_FALLBACK
                    && Boolean.TRUE.equals(previous.getResultJson() != null
                    ? previous.getResultJson().get("ruleCompleted") : null)) {
                return stateAssembler.buildStateResponse(session, null, null);
            }
        }

        if (session.getExecutionMode() != ExecutionMode.RULE) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前不是规则模式");
        }
        if (session.getStatus() != InterviewStatus.AWAITING_ANSWER) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前不在等待回答状态");
        }

        long completedCount = recordRepository.countBySessionId(session.getId());
        int roundNo = (int) completedCount + 1;
        String fingerprint = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);
        Optional<InterviewAiAttempt> roundAttempt = attemptRepository
                .findBySessionIdAndOperationTypeAndRoundNo(session.getId(),
                        AiAttemptOperationType.ANSWER_EVALUATION, roundNo);
        InterviewAiAttempt attempt;
        if (existing.isPresent()) {
            attempt = existing.get();
        } else if (roundAttempt.isPresent()) {
            attempt = roundAttempt.get();
            if (attempt.getStatus() != AiAttemptStatus.RULE_FALLBACK
                    || !Objects.equals(attempt.getRequestFingerprint(), fingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前轮次已有不同的回答操作");
            }
            if (Boolean.TRUE.equals(attempt.getResultJson() != null
                    ? attempt.getResultJson().get("ruleCompleted") : null)) {
                return stateAssembler.buildStateResponse(session, null, null);
            }
        } else {
            attempt = operationSupport.createRuleAttempt(userId, session.getId(), roundNo,
                    idempotencyKey, answerText, fingerprint);
        }

        // 规则评分
        int score = ruleEngine.ruleScore(answerText);
        InterviewRecord record = new InterviewRecord();
        record.setSessionId(session.getId());
        record.setRoundNo(roundNo);
        record.setQuestionText(session.getCurrentQuestion());
        record.setAnswerText(answerText);
        record.setRoundScore(score);
        record.setEvaluationSource(EvaluationSource.RULE);
        record.setAiAttemptId(attempt.getId());
        record.setFeedbackJson(Map.of(
                "dimensionScores", Map.of(
                        "relevance", Math.min(25, score / 4),
                        "evidenceSpecificity", Math.min(25, score / 4),
                        "structureClarity", Math.min(20, score / 5),
                        "roleCompetency", Math.min(20, score / 5),
                        "authenticityReflection", Math.min(10, score / 10)
                ),
                "strengths", score >= 60 ? List.of("回答内容较为完整") : List.of(),
                "improvements", score < 60 ? List.of("建议使用 STAR 原则组织回答，并提供量化结果") : List.of()
        ));
        recordRepository.saveAndFlush(record);

        attempt.setStatus(AiAttemptStatus.RULE_FALLBACK);
        attempt.setAttemptCount(existing.isPresent() ? attempt.getAttemptCount() : 0);
        attempt.setPendingAnswer(null);
        attempt.setResultJson(Map.of("ruleCompleted", true, "roundScore", score));
        attemptRepository.save(attempt);

        int newCount = (int) completedCount + 1;

        if (newCount >= session.getTargetQuestionCount()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.TARGET_REACHED_IN_RULE_MODE);
        } else {
            session.setCurrentQuestion(ruleEngine.nextRuleQuestion(newCount));
        }

        sessionRepository.save(session);
        return stateAssembler.buildStateResponse(session, null, null);
    }
}
