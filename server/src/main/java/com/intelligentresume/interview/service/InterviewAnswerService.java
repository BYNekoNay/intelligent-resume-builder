package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.persistence.AsyncFailureMessages;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
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
 * <p>【小白导读】
 * ① 业务问题：用户在面试页点「提交本题答案」。这个类负责把答案收下来、交给 AI 点评、
 *    点评完写入本轮记录并放出下一题（或宣布面试结束）。
 * ② 链路位置：上游由 {@code InterviewService.answer}（Controller 的 /answer 接口）调用；
 *    下游调 {@link InterviewAiService}（模型）与 {@link InterviewPromptContextAssembler}（组装材料），
 *    产出写入 {@code interview_record}（本轮记录）并更新 {@code interview_session}（会话状态）。
 *    前端拿到的只是「已收到、评估中」，最终结果靠轮询状态接口获取。
 * ③ 改坏它：轻则同一答案被评估两次、扣两次配额（幂等失效）；重则用户的答案丢了
 *    （后台异常没落库）、会话永远停在「评估中」转圈。
 *
 * <p>TX1 校验状态/同意/配额/幂等并创建 attempt（状态置 {@code EVALUATING_ANSWER}）→
 * **把评估提交给后台执行器并立即返回** → 后台完成 AI 调用与 TX2（记录 + outcome）。
 *
 * <p><b>为什么评估要异步</b>：原先在请求线程内同步等待，实测平均 108.7s，
 * 而前端该接口超时仅 60s，4 轮全部超时 → 用户卡在无法前进的面试里。
 * 详见 {@link InterviewEvaluationConfig} 的类注释与
 * {@code docs/reviews/2026-09-25-full-functional-verification.md} §2。
 *
 * <p>术语速查：轮次（round）—— 一道题从出题到作答完评的完整来回，第几轮就是 roundNo；
 * attempt —— 一次 AI 调用的档案（谁发的、什么状态、成功失败），见 {@link InterviewAiAttempt}；
 * 幂等键 —— 客户端为每次操作生成的唯一编号，网络重发时服务端靠它识别「同一次操作」，
 * 不重复扣费、不重复评估。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #answer} 返回时评估<b>通常还没开始</b>；调用方看到的是 {@code EVALUATING_ANSWER}，
 *       终态必须靠轮询 {@code GET /interviews/{id}} 获取。</li>
 *   <li>TX1 里「校验状态 → 建 attempt → 置 EVALUATING_ANSWER」必须在同一事务，
 *       否则并发两次作答会各自建 attempt 并双双通过状态校验。</li>
 *   <li>后台段所有异常都必须落到 attempt（{@link #markEvaluationFailed}），
 *       否则会话会停在 EVALUATING_ANSWER，只能等 getState 的陈旧接管兜底。</li>
 *   <li>轮次号 = 已落库记录数 + 1，而不是读会话上的计数字段；记录写入与轮次号必须同事务。</li>
 * </ul>
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

    /**
     * TX1 结果。{@code outcome} 决定请求线程下一步：
     * PROCEED=需要提交后台评估；其余=直接按已有 attempt 组装响应返回。
     */
    private enum AnswerOutcome { PROCEED, SUCCESS, FAILURE, PROCESSING }

    private record AnswerPreparation(Long sessionId, int roundNo,
                                     AnswerOutcome outcome, Long attemptId) {}

    /**
     * 提交一次回答。
     *
     * <p>【小白导读】核心方法，分「前台一个短事务」+「后台一段评估」两段。
     * 前台（TX1）步骤：①锁会话行 → ②查幂等键，命中则按旧 attempt 的状态原样回放
     * （不重新评估、不重复扣费）→ ③校验会话确实在「等回答」状态且有当前题目
     * → ④校验用户授权（缺授权落一条失败档案）→ ⑤校验今日配额（不足同样落失败档案）
     * → ⑥算轮次号、建 attempt、把会话置为「评估中」。后台步骤：⑦组装评估上下文调模型
     * → ⑧TX2：写本轮记录、attempt 记成功、推进会话（出下一题或收尾）。
     * 顺序不能换：必须先建 attempt 再改会话状态，attempt 是「这轮在做 AI」的唯一凭据；
     * 校验必须在建 attempt 之前，否则失败也要留一条脏档案。
     *
     * <p>幂等语义：同一 Idempotency-Key 且指纹一致时，<b>不重新评估</b>，只按已有 attempt 的状态回放
     * （失败→返回失败信息、处理中→原样返回 EVALUATING_ANSWER、成功→返回当前状态）。
     * 键相同但会话/操作类型/指纹任一不同即 409，避免把 A 的评估结果当作 B 的。
     */
    public InterviewStateResponse answer(Long id, String answerText, Long userId, String idempotencyKey) {
        AnswerPreparation preparation = tx.execute(status -> {
            // 先锁会话行：本事务内会写入状态，且后续所有状态判断都基于锁住后的快照
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> stateAssembler.notFound("面试会话不存在"));

            Optional<InterviewAiAttempt> existing = attemptRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                InterviewAiAttempt previous = existing.get();
                // 指纹用「上一轮的 roundNo」重算：这样同一份答案在同一轮重复提交才能判定为同一请求
                String replayFingerprint = operationSupport.buildFingerprint(id, previous.getRoundNo(), answerText);
                if (!Objects.equals(previous.getSessionId(), id)
                        || previous.getOperationType() != AiAttemptOperationType.ANSWER_EVALUATION
                        || !Objects.equals(previous.getRequestFingerprint(), replayFingerprint)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
                }
                if (previous.getStatus() == AiAttemptStatus.PROCESSING
                        && operationSupport.isStale(previous, LocalDateTime.now())) {
                    // 卡死超过阈值的旧请求：这里顺手判死，让用户能走 /ai/retry 而不是一直看着轮询
                    operationSupport.markAttemptFailed(session, previous, "PROCESSING_TIMEOUT",
                            "AI 请求处理超时，请重试", true, null);
                }
                // 回放不重新调用 AI：PROCESSING 就继续让前端轮询，避免重复评估与重复计费
                return new AnswerPreparation(session.getId(), previous.getRoundNo(),
                        replayOutcome(previous), previous.getId());
            }

            // 只有 AWAITING_ANSWER 才接受作答；EVALUATING_ANSWER 时重复提交会落到上面的幂等分支，
            // 走到这里说明是「另一个」回答，必须拒绝，否则会覆盖正在进行的那一轮
            if (session.getStatus() == InterviewStatus.COMPLETED) {
                throw new BusinessException(ErrorCode.CONFLICT, "面试已完成");
            }
            if (session.getStatus() != InterviewStatus.AWAITING_ANSWER) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前不在等待回答状态");
            }
            // 模式双保险：/answer 的路由只读一次执行模式，快照可能过期（TOCTOU）；降级是单向的，
            // 但并发的 continue-with-rules 理论上可在「路由读之后、本事务加锁之前」提交。
            // 这里以行锁内的权威校验兜底，确保降级为 RULE 的会话绝不会再触发模型调用。
            if (session.getExecutionMode() != ExecutionMode.AI) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前不是 AI 模式");
            }
            if (session.getCurrentQuestion() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前无待回答的问题");
            }

            // 校验同意：与 start 一致，失败也落一条 FAILED attempt（FORBIDDEN、不可重试），
            // 但此时答案不会被保存，用户需授权后重新提交
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
                attempt.setErrorMessage(AsyncFailureMessages.persisted(e.getMessage()));
                attempt.setRetryable(true);
                attemptRepository.save(attempt);
                return new AnswerPreparation(session.getId(), roundNo,
                        AnswerOutcome.FAILURE, attempt.getId());
            }

            long completedCount = recordRepository.countBySessionId(session.getId());
            // 轮次号取「已落库记录数 + 1」：记录只在 TX2 落库，因此并发下同一轮只可能有一个事务拿到同一 roundNo，
            // 再由 (session_id, operation_type, round_no) 唯一约束兜底
            int roundNo = (int) completedCount + 1;
            String fingerprint = operationSupport.buildFingerprint(session.getId(), roundNo, answerText);

            // 先落 attempt（PROCESSING）再改会话状态：attempt 是「这轮在做 AI」的唯一凭据
            InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                    AiAttemptOperationType.ANSWER_EVALUATION, roundNo, idempotencyKey, answerText, fingerprint);

            session.setStatus(InterviewStatus.EVALUATING_ANSWER);
            sessionRepository.save(session);

            return new AnswerPreparation(session.getId(), roundNo,
                    AnswerOutcome.PROCEED, attempt.getId());
        });

        Long sessionId = preparation.sessionId();
        int roundNo = preparation.roundNo();
        // 非 PROCEED：幂等回放或同意/配额失败，本次不再触达模型，直接把现状返回
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
            // 只提交任务，不 join：请求线程立刻返回，避免撞前端 60s 超时
            evaluationExecutor.execute(() -> runEvaluation(sessionId, roundNo, answerText, userId, attemptId));
        } catch (RejectedExecutionException rejected) {
            // 队列已满：快速失败并标记为**可重试**，而不是让会话静默停在 EVALUATING_ANSWER
            // 注意此时 TX1 已提交（attempt 为 PROCESSING、会话为 EVALUATING_ANSWER），
            // 所以必须显式回写失败，否则前端要一直轮询到 600s 陈旧接管才可能脱困
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
            // 两个守卫都必须有：会话状态守卫防止覆盖并发的状态变更，attempt 状态守卫防止
            // 已被陈旧接管判死的 attempt 又被写回 SUCCESS（那会造出「记录已写、attempt 已失败」的矛盾）
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

                // 保存回答记录：questionText 取会话当前题（AI 未换题前它仍是本轮题目），
                // feedbackJson 由评估结果拍平而成，报告与状态接口都从这里回读
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

                // 推进会话：到上限/信息已足够则 COMPLETED，否则写入下一题回到 AWAITING_ANSWER
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

    /**
     * 把失败落到 attempt 并让会话进入 {@code AI_ACTION_REQUIRED}。
     *
     * <p>用新事务而不是复用调用方上下文：调用点可能在后台线程、也可能在异常栈里，
     * 原事务可能已回滚，独立事务可避免失败写入被一并回滚。
     * 连这里都失败时无法再做补偿，只能记日志并依赖 getState 的陈旧接管。
     */
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

    /**
     * 幂等回放时的状态映射。{@code RULE_FALLBACK} 视为成功（该轮结论已经落库，只是来源是规则），
     * {@code PROCESSING} 单独返回，调用方据此决定「继续轮询」而不是再发一次评估。
     */
    private AnswerOutcome replayOutcome(InterviewAiAttempt attempt) {
        return switch (attempt.getStatus()) {
            case FAILED -> AnswerOutcome.FAILURE;
            case PROCESSING -> AnswerOutcome.PROCESSING;
            case SUCCESS, RULE_FALLBACK -> AnswerOutcome.SUCCESS;
        };
    }
}
