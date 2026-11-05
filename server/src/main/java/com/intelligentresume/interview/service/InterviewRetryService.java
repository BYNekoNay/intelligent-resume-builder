package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.persistence.AsyncFailureMessages;
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

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 重试流程：两阶段短事务 + 事务外 AI。
 *
 * <p>含 retryGeneration 计数（attemptCount）与 stale 丢弃判定，须整体保留。
 * 日志只记录 id/状态/错误码。
 *
 * <p>要点：
 * <ul>
 *   <li>重试是<b>原地重跑同一条 attempt</b>：不新建 attempt、不重新组装 prompt，因此不要求预览令牌。</li>
 *   <li>只有 {@code AI_ACTION_REQUIRED} 且存在最近一次失败 attempt 时才允许重试。</li>
 *   <li>{@code retryable=false} 的失败默认不可重试，唯一例外是 {@code FORBIDDEN} —— 若用户此刻
 *       已补上同意，则允许把 retryable 翻回 true 继续。</li>
 *   <li>代数（attemptCount）是判断「结果是否过期」的核心依据：TX1 里 +1 得到本次代数，
 *       若期间发生契约修复则代数再 +1，后台落地前必须用最新代数比对。</li>
 *   <li>后台段异常必须落到 attempt，否则会话停在 GENERATING_QUESTION / EVALUATING_ANSWER。</li>
 * </ul>
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
    /** 承载事务外的 AI 重试；生产为有界线程池，测试可注入同步/可控执行器以保证确定性。 */
    private final Executor evaluationExecutor;

    public InterviewRetryService(InterviewSessionRepository sessionRepository,
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

    public InterviewStateResponse retryAi(Long id, Long userId) {
        // TX1: 校验 + 增加重试计数
        // 返回值用 long[] 传递 5 个标量（sessionId/attemptId/roundNo/opType/generation），
        // 下标含义与下方解构一一对应，改动顺序必须两边同改。
        long[] phase1Result = tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

            // 只有「AI 失败待处理」才可重试；生成中/评估中再点重试会与进行中的调用打架
            if (session.getStatus() != InterviewStatus.AI_ACTION_REQUIRED) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许重试");
            }

            InterviewAiAttempt lastFailed = stateAssembler.latestFailedAttempt(session.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到失败的操作记录"));

            // 不可重试的失败默认直接拒绝。FORBIDDEN 是例外：它代表「缺授权」，
            // 用户可能刚刚补签，此时补签后再重试是合法且用户预期的行为。
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
                // 配额不足，保持 AI_ACTION_REQUIRED；只更新错误码与文案，不改状态、不发起调用。
                // 这里的 -1 只占位（roundNo 在此分支不会被使用）。
                lastFailed.setErrorMessage(AsyncFailureMessages.persisted(e.getMessage()));
                lastFailed.setErrorCode("RATE_LIMITED");
                attemptRepository.save(lastFailed);
                return new long[]{session.getId(), lastFailed.getId(), -1,
                        lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION ? 0 : 1,
                        lastFailed.getAttemptCount()};
            }

            // 复用同一条 attempt：代数 +1 表示「新的一次重试」，并回到 PROCESSING
            lastFailed.setAttemptCount(lastFailed.getAttemptCount() + 1);
            lastFailed.setStatus(AiAttemptStatus.PROCESSING);
            attemptRepository.save(lastFailed);

            // 会话状态按操作类型回到对应的「进行中」状态，前端据此继续轮询
            if (lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION) {
                session.setStatus(InterviewStatus.GENERATING_QUESTION);
            } else {
                session.setStatus(InterviewStatus.EVALUATING_ANSWER);
            }
            sessionRepository.save(session);

            // opType 编码为 0=首题 / 1=评估，后续跨线程按此分支
            long opType = lastFailed.getOperationType() == AiAttemptOperationType.INITIAL_QUESTION ? 0 : 1;
            return new long[]{session.getId(), lastFailed.getId(), lastFailed.getRoundNo() != null ? lastFailed.getRoundNo() : 0,
                    opType, lastFailed.getAttemptCount()};
        });

        Long sessionId = phase1Result[0];
        Long attemptId = phase1Result[1];
        int roundNo = (int) phase1Result[2];
        long opType = phase1Result[3];
        int retryGeneration = (int) phase1Result[4];

        // 配额失败：TX1 只改了错误码未改状态，这里按 FAILED 组装 aiFailure 返回（不提交后台任务）
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

        // 事务外：把 AI 重试**提交到后台执行器**，本请求立即返回 PROCESSING 状态。
        // 前端在 EVALUATING_ANSWER / GENERATING_QUESTION 下会轮询 GET /interviews/{id}
        // （InterviewView.scheduleStatePoll），故无需改前端即可从「同步等待 108s」变为「秒回 + 轮询取终态」。
        try {
            evaluationExecutor.execute(() -> runRetry(sessionId, userId, attemptId, roundNo, opType, retryGeneration));
        } catch (RejectedExecutionException rejected) {
            // 队列已满：快速失败并标记为**可重试**，而不是让会话静默停在 PROCESSING
            log.warn("interview AI retry rejected (queue full): sessionId={} attemptId={}", sessionId, attemptId);
            markRetryFailed(sessionId, userId, attemptId, opType, retryGeneration,
                    "QUEUE_REJECTED", "AI 重试队列繁忙，请稍后重试", true, null);
            InterviewSession rejectedSession = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt rejectedAttempt = attemptRepository.findById(attemptId).orElseThrow();
            return stateAssembler.buildStateResponse(rejectedSession, null,
                    stateAssembler.buildAiFailure(rejectedAttempt));
        }

        return stateAssembler.buildStateResponse(checkSession, null, null);
    }

    /**
     * 后台执行 AI 重试（首题生成或回答评估），不向调用方返回状态，终态由前端轮询获取。
     *
     * <p>原实现在请求线程内同步等待 AI：与 {@code /answer} 修复前同源，实测评估平均 108.7s，
     * 而前端该接口超时 60s ⇒ 用户点「重试」必然超时、服务端仍在评估。此处与
     * {@code InterviewAnswerService.runEvaluation} 保持同一形态：任何异常都必须落到 attempt 上，
     * 否则会话会一直停在 PROCESSING（虽有陈旧超时兜底，但不该依赖它）。
     */
    private void runRetry(Long sessionId, Long userId, Long attemptId, int roundNo, long opType, int generation) {
        // 用单元素数组而不是局部变量：闭包（reserveRepairCall 回调）里要回写代数
        int[] retryGeneration = {generation};
        try {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            if (opType == 0) {
                // 首题重试：输入与首次生成完全相同（同一会话的上下文），因此不需要重建 prompt
                var initialCall = operationSupport.callAiForFirstQuestion(session, userId);
                String question = initialCall.value().getQuestion();

                tx.executeWithoutResult(s -> {
                    InterviewSession locked = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                    InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                    // 代数/状态不符说明期间又有新的重试或陈旧接管，本次结果作废
                    operationSupport.assertRetryStillCurrent(locked, attempt, retryGeneration[0],
                            InterviewStatus.GENERATING_QUESTION);
                    locked.setCurrentQuestion(question);
                    locked.setStatus(InterviewStatus.AWAITING_ANSWER);
                    sessionRepository.save(locked);

                    attempt.setStatus(AiAttemptStatus.SUCCESS);
                    attempt.setResultJson(Map.of("question", question));
                    attempt.setPendingAnswer(null);
                    attempt.setProviderRequestId(initialCall.providerRequestId());
                    attemptRepository.save(attempt);
                });
            } else {
                // 回答评估重试：答案从 attempt.pendingAnswer 取回 —— 这就是重试「不要求客户端重传」的原因，
                // 也意味着 pendingAnswer 缺失时无法恢复，只能按 AI_FAILURE 失败
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                String pendingAnswer = attempt.getPendingAnswer();
                if (pendingAnswer == null) {
                    throw new BusinessException(ErrorCode.AI_FAILURE, "缺少待评估的回答");
                }
                var evaluationCall = interviewAiService.evaluateAnswer(
                        promptContextAssembler.buildEvaluationContext(session, pendingAnswer, userId),
                        session.getOutputLanguage(),
                        () -> {
                            // 契约修复会多发一次模型调用：配额 +1，同时本地代数 +1，二者必须同步，
                            // 否则最终落地时的 assertRetryStillCurrent 会把合法结果判成过期
                            operationSupport.reserveRepairCall(userId, attemptId);
                            retryGeneration[0] += 1;
                        });
                var evaluation = evaluationCall.value();
                operationSupport.validateEvaluationProgress(session, roundNo, evaluation,
                        evaluationCall.providerRequestId());

                tx.executeWithoutResult(s -> {
                    InterviewSession locked = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                    InterviewAiAttempt att = attemptRepository.findById(attemptId).orElseThrow();
                    operationSupport.assertRetryStillCurrent(locked, att, retryGeneration[0],
                            InterviewStatus.EVALUATING_ANSWER);

                    int totalScore = evaluation.getDimensionScores().total();
                    // 沿用 TX1 传下来的轮次号，而不是重新计数：重试不能把同一轮算成新一轮
                    int actualRoundNo = roundNo;

                    InterviewRecord record = new InterviewRecord();
                    record.setSessionId(locked.getId());
                    record.setRoundNo(actualRoundNo);
                    record.setQuestionText(locked.getCurrentQuestion());
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

                    operationSupport.applyEvaluationOutcome(locked, actualRoundNo, evaluation);
                    sessionRepository.save(locked);
                });
            }
        } catch (BusinessException e) {
            log.warn("interview AI retry failed: sessionId={} attemptId={} code={} msg={}",
                    sessionId, attemptId, e.getErrorCode(), e.getMessage());
            markRetryFailed(sessionId, userId, attemptId, opType, retryGeneration[0],
                    e.getErrorCode().name(), e.getMessage(), operationSupport.isRetryable(e),
                    operationSupport.providerRequestId(e));
        } catch (RuntimeException unexpected) {
            // 兜底：未预期异常也必须落到 attempt，避免会话永久停在 PROCESSING
            log.error("interview AI retry crashed: sessionId={} attemptId={}", sessionId, attemptId, unexpected);
            markRetryFailed(sessionId, userId, attemptId, opType, retryGeneration[0],
                    "UNEXPECTED", "AI 重试失败，请重试", true, null);
        }
    }

    /**
     * 把重试失败落到 attempt（含 stale 丢弃判定：被更新的重试/动作取代时只记日志，不覆盖新状态）。
     */
    private void markRetryFailed(Long sessionId, Long userId, Long attemptId, long opType, int generation,
                                 String errorCode, String errorMessage, boolean retryable, String providerRequestId) {
        try {
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                InterviewStatus expectedStatus = opType == 0
                        ? InterviewStatus.GENERATING_QUESTION : InterviewStatus.EVALUATING_ANSWER;
                if (operationSupport.isCurrentRetry(session, attempt, generation, expectedStatus)) {
                    operationSupport.markAttemptFailed(session, attempt, errorCode, errorMessage,
                            retryable, providerRequestId);
                } else {
                    log.info("Discarded stale interview AI retry result: sessionId={}, attemptId={}, generation={}",
                            sessionId, attemptId, generation);
                }
            });
        } catch (RuntimeException fallbackFailure) {
            // 连「标记失败」都失败时只能记日志：会话最终由 getState 的陈旧超时兜底
            log.error("failed to mark interview AI retry as failed: sessionId={} attemptId={}",
                    sessionId, attemptId, fallbackFailure);
        }
    }
}
