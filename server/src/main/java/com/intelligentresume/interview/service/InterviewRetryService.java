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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * AI 重试流程：两阶段短事务 + 事务外 AI。
 *
 * <p>含 retryGeneration 计数（attemptCount）与 stale 丢弃判定，须整体保留。
 * 日志只记录 id/状态/错误码。
 */
@Service
public class InterviewRetryService {

    private static final Logger log = LoggerFactory.getLogger(InterviewRetryService.class);

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final InterviewAiService interviewAiService;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewRetryService(InterviewSessionRepository sessionRepository,
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

    public InterviewStateResponse retryAi(Long id, Long userId) {
        // TX1: 校验 + 增加重试计数
        long[] phase1Result = tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

            if (session.getStatus() != InterviewStatus.AI_ACTION_REQUIRED) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许重试");
            }

            InterviewAiAttempt lastFailed = stateAssembler.latestFailedAttempt(session.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到失败的操作记录"));

            if (!lastFailed.getRetryable()) {
                boolean consentRestored = "FORBIDDEN".equals(lastFailed.getErrorCode())
                        && operationSupport.hasInterviewConsent(userId, session);
                if (!consentRestored) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "该操作不可重试");
                }
                lastFailed.setRetryable(true);
            }

            // 校验配额
            try {
                operationSupport.checkInterviewQuota(userId);
            } catch (BusinessException e) {
                // 配额不足，保持 AI_ACTION_REQUIRED
                lastFailed.setErrorMessage(e.getMessage());
                lastFailed.setErrorCode("RATE_LIMITED");
                attemptRepository.save(lastFailed);
                return new long[]{session.getId(), lastFailed.getId(), -1,
                        lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION ? 0 : 1,
                        lastFailed.getAttemptCount()};
            }

            lastFailed.setAttemptCount(lastFailed.getAttemptCount() + 1);
            lastFailed.setStatus(AiAttemptStatus.PROCESSING);
            attemptRepository.save(lastFailed);

            if (lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION) {
                session.setStatus(InterviewStatus.GENERATING_QUESTION);
            } else {
                session.setStatus(InterviewStatus.EVALUATING_ANSWER);
            }
            sessionRepository.save(session);

            long opType = lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION ? 0 : 1;
            return new long[]{session.getId(), lastFailed.getId(), lastFailed.getRoundNo() != null ? lastFailed.getRoundNo() : 0,
                    opType, lastFailed.getAttemptCount()};
        });

        Long sessionId = phase1Result[0];
        Long attemptId = phase1Result[1];
        int roundNo = (int) phase1Result[2];
        long opType = phase1Result[3];
        int[] retryGeneration = {(int) phase1Result[4]};

        // 配额失败
        InterviewSession checkSession = sessionRepository.findById(sessionId).orElseThrow();
        if (checkSession.getStatus() == InterviewStatus.AI_ACTION_REQUIRED) {
            InterviewAiAttempt failedAttempt = attemptRepository.findById(attemptId).orElseThrow();
            if (failedAttempt.getStatus() == AiAttemptStatus.FAILED) {
                return stateAssembler.buildStateResponse(checkSession, null,
                        stateAssembler.buildAiFailure(attemptId,
                                opType == 0 ? "INITIAL_QUESTION" : "ANSWER_EVALUATION",
                                new BusinessException(ErrorCode.RATE_LIMITED, failedAttempt.getErrorMessage())));
            }
        }

        // Phase 2: 事务外调用 AI
        try {
            if (opType == 0) {
                // 首题重试
                var initialCall = operationSupport.callAiForFirstQuestion(checkSession, userId);
                String question = initialCall.value().getQuestion();

                tx.executeWithoutResult(s -> {
                    InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                    InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                    operationSupport.assertRetryStillCurrent(session, attempt, retryGeneration[0],
                            InterviewStatus.GENERATING_QUESTION);
                    session.setCurrentQuestion(question);
                    session.setStatus(InterviewStatus.AWAITING_ANSWER);
                    sessionRepository.save(session);

                    attempt.setStatus(AiAttemptStatus.SUCCESS);
                    attempt.setResultJson(Map.of("question", question));
                    attempt.setPendingAnswer(null);
                    attempt.setProviderRequestId(initialCall.providerRequestId());
                    attemptRepository.save(attempt);
                });
            } else {
                // 回答评估重试
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                String pendingAnswer = attempt.getPendingAnswer();
                if (pendingAnswer == null) {
                    throw new BusinessException(ErrorCode.AI_FAILURE, "缺少待评估的回答");
                }
                var evaluationCall = interviewAiService.evaluateAnswer(
                        promptContextAssembler.buildEvaluationContext(checkSession, pendingAnswer, userId),
                        checkSession.getOutputLanguage(),
                        () -> {
                            operationSupport.reserveRepairCall(userId, attemptId);
                            retryGeneration[0] += 1;
                        });
                var evaluation = evaluationCall.value();
                operationSupport.validateEvaluationProgress(checkSession, roundNo, evaluation,
                        evaluationCall.providerRequestId());

                tx.executeWithoutResult(s -> {
                    InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                    InterviewAiAttempt att = attemptRepository.findById(attemptId).orElseThrow();
                    operationSupport.assertRetryStillCurrent(session, att, retryGeneration[0],
                            InterviewStatus.EVALUATING_ANSWER);

                    int totalScore = evaluation.getDimensionScores().total();
                    int actualRoundNo = roundNo;

                    InterviewRecord record = new InterviewRecord();
                    record.setSessionId(session.getId());
                    record.setRoundNo(actualRoundNo);
                    record.setQuestionText(session.getCurrentQuestion());
                    record.setAnswerText(pendingAnswer);
                    record.setRoundScore(totalScore);
                    record.setEvaluationSource(EvaluationSource.AI);
                    record.setAiAttemptId(attemptId);
                    record.setFeedbackJson(operationSupport.buildAiFeedback(evaluation));
                    recordRepository.saveAndFlush(record);

                    att.setStatus(AiAttemptStatus.SUCCESS);
                    att.setPendingAnswer(null);
                    att.setResultJson(Map.of("roundScore", totalScore));
                    att.setProviderRequestId(evaluationCall.providerRequestId());
                    attemptRepository.save(att);

                    operationSupport.applyEvaluationOutcome(session, actualRoundNo, evaluation);
                    sessionRepository.save(session);
                });
            }
        } catch (BusinessException e) {
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                InterviewStatus expectedStatus = opType == 0
                        ? InterviewStatus.GENERATING_QUESTION : InterviewStatus.EVALUATING_ANSWER;
                if (operationSupport.isCurrentRetry(session, attempt, retryGeneration[0], expectedStatus)) {
                    operationSupport.markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                            operationSupport.isRetryable(e), operationSupport.providerRequestId(e));
                } else {
                    log.info("Discarded stale interview AI retry result: sessionId={}, attemptId={}, generation={}",
                            id, attemptId, retryGeneration[0]);
                }
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewStateResponse.AiFailureInfo failure = failed.getStatus() == InterviewStatus.AI_ACTION_REQUIRED
                    ? stateAssembler.latestFailedAttempt(sessionId).map(stateAssembler::buildAiFailure).orElse(null) : null;
            return stateAssembler.buildStateResponse(failed, null, failure);
        }

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        return stateAssembler.buildStateResponse(session, null, null);
    }
}
