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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 面试回答流程：两阶段短事务 + 事务外评估 AI（评估在**后台执行器**中运行）。
 *
 * <p>TX1 校验状态/同意/配额/幂等并创建 attempt（状态置 {@code EVALUATING_ANSWER}）→
 * **把评估提交给后台执行器并立即返回** → 后台完成 AI 调用与 TX2（记录 + outcome）。
 *
 * <p><b>为什么评估要异步</b>：原先在请求线程内同步等待，实测平均 108.7s，
 * 而前端该接口超时仅 60s，4 轮全部超时 → 用户卡在无法前进的面试里。
 * 详见 {@link InterviewEvaluationConfig} 的类注释与
 * {@code docs/reviews/2026-09-25-full-functional-verification.md} §2。
 */
@Service
public class InterviewAnswerService {

    private static final Logger log = LoggerFactory.getLogger(InterviewAnswerService.class);

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final InterviewAiService interviewAiService;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final InterviewOperationSupport operationSupport;
    /** 承载事务外的 AI 评估；生产为有界线程池，测试可注入同步执行器以保证确定性。 */
    private final Executor evaluationExecutor;

    public InterviewAnswerService(InterviewSessionRepository sessionRepository,
                                  InterviewRecordRepository recordRepository,
                                  InterviewAiAttemptRepository attemptRepository,
                                  InterviewAiService interviewAiService,
                                  TransactionTemplate tx,
                                  InterviewStateAssembler stateAssembler,
                                  InterviewPromptContextAssembler promptContextAssembler,
                                  InterviewOperationSupport operationSupport,
                                  @Qualifier("interviewEvaluationExecutor") Executor evaluationExecutor) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
        this.interviewAiService = interviewAiService;
        this.tx = tx;
        this.stateAssembler = stateAssembler;
        this.promptContextAssembler = promptContextAssembler;
        this.operationSupport = operationSupport;
        this.evaluationExecutor = evaluationExecutor;
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

        // 事务外：把 AI 评估**提交到后台执行器**，本请求立即返回 EVALUATING_ANSWER。
        // 前端在该状态下会轮询 GET /interviews/{id}（InterviewView.scheduleStatePoll），
        // 因此无需改动前端，即可从"同步等待 108s"变为"秒回 + 轮询取终态"。
        Long attemptId = preparation.attemptId();
        try {
            evaluationExecutor.execute(() -> runEvaluation(sessionId, roundNo, answerText, userId, attemptId));
        } catch (RejectedExecutionException rejected) {
            // 队列已满：快速失败并标记为**可重试**，而不是让会话静默停在 EVALUATING_ANSWER
            log.warn("interview evaluation rejected (queue full): sessionId={} attemptId={}",
                    sessionId, attemptId);
            markEvaluationFailed(sessionId, userId, attemptId, "QUEUE_REJECTED",
                    "AI 评估队列繁忙，请稍后重试", true, null);
            InterviewSession rejectedSession = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt rejectedAttempt = attemptRepository.findById(attemptId).orElseThrow();
            return stateAssembler.buildStateResponse(rejectedSession, null,
                    stateAssembler.buildAiFailure(rejectedAttempt));
        }

        InterviewSession evaluating = sessionRepository.findById(sessionId).orElseThrow();
        return stateAssembler.buildStateResponse(evaluating, null, null);
    }

    /**
     * 后台执行 AI 评估并落库（原 {@link #answer} 的"事务外评估 + TX2"，抽出以便异步执行）。
     *
     * <p>本方法在**后台线程**运行、不持有请求上下文，故所有入参显式传入、也不向调用方返回状态；
     * 前端通过轮询 {@code GET /interviews/{id}} 获取终态。任何未预期异常都必须在此兜住并落到
     * attempt 上，否则会话会一直停在 {@code EVALUATING_ANSWER}（虽有陈旧超时兜底，但不该依赖它）。
     */
    private void runEvaluation(Long sessionId, int roundNo, String answerText, Long userId, Long attemptId) {
        try {
            InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> evaluationCall =
                    interviewAiService.evaluateAnswer(
                            promptContextAssembler.buildEvaluationContext(afterTx1, answerText, userId),
                            afterTx1.getOutputLanguage(),
                            () -> operationSupport.reserveRepairCall(userId, attemptId));
            operationSupport.validateEvaluationProgress(afterTx1, roundNo, evaluationCall.value(),
                    evaluationCall.providerRequestId());

            // TX2: 保存结果
            InterviewCoachResponse.AnswerEvaluation evaluationResult = evaluationCall.value();
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow();
                if (session.getStatus() != InterviewStatus.EVALUATING_ANSWER) {
                    throw new BusinessException(ErrorCode.CONFLICT, "会话状态已变更");
                }

                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
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

                operationSupport.applyEvaluationOutcome(session, roundNo, evaluationResult);

                sessionRepository.save(session);
            });
        } catch (BusinessException e) {
            // AI 失败 → AI_ACTION_REQUIRED；失败原因经 aiFailure 暴露给前端（含 retryable / reauthorizationRequired）
            log.warn("interview evaluation failed: sessionId={} attemptId={} code={} msg={}",
                    sessionId, attemptId, e.getErrorCode(), e.getMessage());
            markEvaluationFailed(sessionId, userId, attemptId, e.getErrorCode().name(), e.getMessage(),
                    operationSupport.isRetryable(e), operationSupport.providerRequestId(e));
        } catch (RuntimeException unexpected) {
            // 兜底：未预期异常也必须落到 attempt，避免会话永久停在 EVALUATING_ANSWER
            log.error("interview evaluation crashed: sessionId={} attemptId={}", sessionId, attemptId,
                    unexpected);
            markEvaluationFailed(sessionId, userId, attemptId, "UNEXPECTED", "AI 评估失败，请重试",
                    true, null);
        }
    }

    private void markEvaluationFailed(Long sessionId, Long userId, Long attemptId, String errorCode,
                                      String errorMessage, boolean retryable, String providerRequestId) {
        try {
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                operationSupport.markAttemptFailed(session, attempt, errorCode, errorMessage,
                        retryable, providerRequestId);
            });
        } catch (RuntimeException fallbackFailure) {
            // 连"标记失败"都失败时只能记日志：会话最终由 getState 的陈旧超时兜底
            log.error("failed to mark interview attempt as failed: sessionId={} attemptId={}",
                    sessionId, attemptId, fallbackFailure);
        }
    }

    private AnswerOutcome replayOutcome(InterviewAiAttempt attempt) {
        return switch (attempt.getStatus()) {
            case FAILED -> AnswerOutcome.FAILURE;
            case PROCESSING -> AnswerOutcome.PROCESSING;
            case SUCCESS, RULE_FALLBACK -> AnswerOutcome.SUCCESS;
        };
    }
}
