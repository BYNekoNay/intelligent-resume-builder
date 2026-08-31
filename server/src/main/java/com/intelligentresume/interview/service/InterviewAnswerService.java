package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 面试回答流程：两阶段短事务 + 事务外评估 AI。
 *
 * <p>TX1 校验状态/同意/配额/幂等并创建 attempt → 事务外调用评估 AI → TX2 记录+outcome。
 */
@Service
public class InterviewAnswerService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final InterviewAiService interviewAiService;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewAnswerService(InterviewSessionRepository sessionRepository,
                                  InterviewRecordRepository recordRepository,
                                  InterviewAiAttemptRepository attemptRepository,
                                  InterviewAiService interviewAiService,
                                  TransactionTemplate tx,
                                  InterviewStateAssembler stateAssembler,
                                  InterviewPromptContextAssembler promptContextAssembler,
                                  InterviewOperationSupport operationSupport) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
        this.interviewAiService = interviewAiService;
        this.tx = tx;
        this.stateAssembler = stateAssembler;
        this.promptContextAssembler = promptContextAssembler;
        this.operationSupport = operationSupport;
    }

    private enum AnswerOutcome { PROCEED, SUCCESS, FAILURE, PROCESSING }

    private record AnswerPreparation(Long sessionId, int roundNo,
                                     AnswerOutcome outcome, Long attemptId) {}

    public InterviewStateResponse answer(Long id, String answerText, Long userId, String idempotencyKey) {
        AnswerPreparation preparation = tx.execute(status -> {
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
                if (previous.getStatus() == AiAttemptStatus.PROCESSING
                        && operationSupport.isStale(previous, LocalDateTime.now())) {
                    operationSupport.markAttemptFailed(session, previous, "PROCESSING_TIMEOUT",
                            "AI 请求处理超时，请重试", true, null);
                }
                return new AnswerPreparation(session.getId(), previous.getRoundNo(),
                        replayOutcome(previous), previous.getId());
            }

            if (session.getStatus() == InterviewStatus.COMPLETED) {
                throw new BusinessException(ErrorCode.CONFLICT, "面试已完成");
            }
            if (session.getStatus() != InterviewStatus.AWAITING_ANSWER) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前不在等待回答状态");
            }
            if (session.getCurrentQuestion() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前无待回答的问题");
            }

            // 校验同意
            if (!operationSupport.hasInterviewConsent(userId, session)) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                long completedCount = recordRepository.countBySessionId(session.getId());
                int roundNo = (int) completedCount + 1;
                String fp = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                        AiAttemptOperationType.ANSWER_EVALUATION, roundNo, idempotencyKey, answerText, fp);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("FORBIDDEN");
                attempt.setErrorMessage("需要 AI 面试授权");
                attempt.setRetryable(false);
                attemptRepository.save(attempt);
                return new AnswerPreparation(session.getId(), roundNo,
                        AnswerOutcome.FAILURE, attempt.getId());
            }

            // 校验配额
            try {
                operationSupport.checkInterviewQuota(userId);
            } catch (BusinessException e) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                long completedCount = recordRepository.countBySessionId(session.getId());
                int roundNo = (int) completedCount + 1;
                String fp = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                        AiAttemptOperationType.ANSWER_EVALUATION, roundNo, idempotencyKey, answerText, fp);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("RATE_LIMITED");
                attempt.setErrorMessage(e.getMessage());
                attempt.setRetryable(true);
                attemptRepository.save(attempt);
                return new AnswerPreparation(session.getId(), roundNo,
                        AnswerOutcome.FAILURE, attempt.getId());
            }

            long completedCount = recordRepository.countBySessionId(session.getId());
            int roundNo = (int) completedCount + 1;
            String fingerprint = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);

            InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                    AiAttemptOperationType.ANSWER_EVALUATION, roundNo, idempotencyKey, answerText, fingerprint);

            session.setStatus(InterviewStatus.EVALUATING_ANSWER);
            sessionRepository.save(session);

            return new AnswerPreparation(session.getId(), roundNo,
                    AnswerOutcome.PROCEED, attempt.getId());
        });

        Long sessionId = preparation.sessionId();
        int roundNo = preparation.roundNo();
        if (preparation.outcome() != AnswerOutcome.PROCEED) {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            InterviewStateResponse.AiFailureInfo failure = attempt.getStatus() == AiAttemptStatus.FAILED
                    ? stateAssembler.buildAiFailure(attempt) : null;
            return stateAssembler.buildStateResponse(session, null, failure);
        }

        // Phase 2: 事务外调用 AI
        InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
        InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> evaluationCall;
        try {
            evaluationCall = interviewAiService.evaluateAnswer(
                    promptContextAssembler.buildEvaluationContext(afterTx1, answerText, userId),
                    afterTx1.getOutputLanguage(),
                    () -> operationSupport.reserveRepairCall(userId, preparation.attemptId()));
            operationSupport.validateEvaluationProgress(afterTx1, roundNo, evaluationCall.value(),
                    evaluationCall.providerRequestId());
        } catch (BusinessException e) {
            // AI 失败 → 转入 AI_ACTION_REQUIRED
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                operationSupport.markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                        operationSupport.isRetryable(e), operationSupport.providerRequestId(e));
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt failedAttempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            return stateAssembler.buildStateResponse(failed, null, stateAssembler.buildAiFailure(failedAttempt));
        }

        // TX2: 保存结果
        InterviewCoachResponse.AnswerEvaluation evaluationResult = evaluationCall.value();
        tx.executeWithoutResult(s -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
            if (session.getStatus() != InterviewStatus.EVALUATING_ANSWER) {
                throw new BusinessException(ErrorCode.CONFLICT, "会话状态已变更");
            }

            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            if (attempt.getStatus() != AiAttemptStatus.PROCESSING) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI 操作已失效");
            }

            int totalScore = evaluationResult.getDimensionScores().total();

            // 保存回答记录
            InterviewRecord record = new InterviewRecord();
            record.setSessionId(session.getId());
            record.setRoundNo(roundNo);
            record.setQuestionText(session.getCurrentQuestion());
            record.setAnswerText(answerText);
            record.setRoundScore(totalScore);
            record.setEvaluationSource(EvaluationSource.AI);
            record.setAiAttemptId(attempt.getId());
            record.setFeedbackJson(operationSupport.buildAiFeedback(evaluationResult));
            recordRepository.saveAndFlush(record);

            // 更新 attempt
            attempt.setStatus(AiAttemptStatus.SUCCESS);
            attempt.setResultJson(Map.of("roundScore", totalScore));
            attempt.setPendingAnswer(null);
            attempt.setProviderRequestId(evaluationCall.providerRequestId());
            attemptRepository.save(attempt);

            int newCompletedCount = roundNo;

            operationSupport.applyEvaluationOutcome(session, newCompletedCount, evaluationResult);

            sessionRepository.save(session);
        });

        InterviewSession session = sessionRepository.findById(id).orElseThrow();
        return stateAssembler.buildStateResponse(session, null, null);
    }

    private AnswerOutcome replayOutcome(InterviewAiAttempt attempt) {
        return switch (attempt.getStatus()) {
            case FAILED -> AnswerOutcome.FAILURE;
            case PROCESSING -> AnswerOutcome.PROCESSING;
            case SUCCESS, RULE_FALLBACK -> AnswerOutcome.SUCCESS;
        };
    }
}
