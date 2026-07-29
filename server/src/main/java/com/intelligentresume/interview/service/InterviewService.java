package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.*;
import com.intelligentresume.interview.dto.*;
import com.intelligentresume.interview.repository.*;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 面试服务：两阶段短事务 + 事务外 AI 调用。
 *
 * <p>每个变更操作遵循：
 * <ol>
 *   <li>短事务校验所有权、状态、同意、配额、幂等，创建再执行记录</li>
 *   <li>事务外调用 AI</li>
 *   <li>短事务重新锁定会话、确认操作有效、落库</li>
 * </ol>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private static final int DEFAULT_TARGET = 6;
    private static final int MIN_TARGET = 4;
    private static final int MAX_TARGET = 12;
    private static final int MAX_RULE_TOPICS = 18;
    private static final long PROCESSING_TAKEOVER_SECONDS = 75;

    private static final List<String> RULE_TOPICS = List.of(
            "自我介绍", "求职动机", "核心项目", "困难问题", "技术或业务取舍",
            "岗位技能", "协作冲突", "失败复盘", "优先级管理", "利益相关者沟通",
            "学习能力", "主人翁意识", "量化结果", "不确定性处理", "质量与风险",
            "反馈处理", "职业目标", "候选人提问"
    );

    private static final String RULE_FIRST_QUESTION = "请用两分钟介绍你的核心经历、专业优势和职业目标。";
    private static final String RULE_NEXT_TEMPLATE = "请分享一个关于「%s」的具体经历或思考。";

    private final InterviewSessionRepository sessionRepository;
    private final InterviewRecordRepository recordRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final AiConsentService consentService;
    private final InterviewAiService interviewAiService;
    private final InterviewContextSanitizer sanitizer;
    private final AiProviderRegistry providerRegistry;
    private final TransactionTemplate tx;
    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final int interviewDailyQuota;

    public InterviewService(InterviewSessionRepository sessionRepository,
                            InterviewRecordRepository recordRepository,
                            InterviewAiAttemptRepository attemptRepository,
                            AiConsentService consentService,
                            InterviewAiService interviewAiService,
                            InterviewContextSanitizer sanitizer,
                            AiProviderRegistry providerRegistry,
                            TransactionTemplate tx,
                            UserRepository userRepository,
                            ResumeRepository resumeRepository,
                            ResumeVersionRepository resumeVersionRepository,
                            JobDescriptionRepository jobDescriptionRepository,
                            @Value("${app.ai.quota.INTERVIEW_COACH:60}") int interviewDailyQuota) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.attemptRepository = attemptRepository;
        this.consentService = consentService;
        this.interviewAiService = interviewAiService;
        this.sanitizer = sanitizer;
        this.providerRegistry = providerRegistry;
        this.tx = tx;
        this.userRepository = userRepository;
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.interviewDailyQuota = interviewDailyQuota;
    }

    // ==================== 开始面试 ====================

    public InterviewStateResponse start(StartInterviewRequest request, Long userId, String idempotencyKey) {
        // 计算题数
        int target = request.targetQuestionCount() != null
                ? Math.max(MIN_TARGET, Math.min(MAX_TARGET, request.targetQuestionCount()))
                : DEFAULT_TARGET;
        int minQ = (int) Math.ceil(target * 0.5);
        int maxQ = (int) Math.floor(target * 1.5);
        InterviewOutputLanguage outputLanguage = request.outputLanguage() != null
                ? request.outputLanguage() : InterviewOutputLanguage.ZH_CN;

        // 验证来源
        validateSource(request, userId);

        // 构建请求指纹
        String fingerprint = buildStartFingerprint(userId, request);

        // TX1: 创建会话 + 校验同意/配额/幂等
        StartPreparation preparation = tx.execute(status -> {
            // 幂等检查：相同键 + 相同指纹 → 返回已有结果
            Optional<InterviewAiAttempt> existing = attemptRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                InterviewAiAttempt prev = existing.get();
                if (prev.getOperationType() != AiAttemptOperationType.INITIAL_QUESTION
                        || !Objects.equals(prev.getRequestFingerprint(), fingerprint)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
                }
                InterviewSession previousSession = sessionRepository
                        .findByIdAndUserIdForUpdate(prev.getSessionId(), userId).orElseThrow();
                if (prev.getStatus() == AiAttemptStatus.PROCESSING
                        && isStale(prev, LocalDateTime.now())) {
                    markAttemptFailed(previousSession, prev, "PROCESSING_TIMEOUT",
                            "AI 请求处理超时，请重试", true, null);
                }
                return new StartPreparation(previousSession.getId(), true, prev.getId());
            }

            InterviewSession session = new InterviewSession();
            session.setUserId(userId);
            session.setSourceType(request.sourceType());
            session.setResumeVersionId(request.sourceType() == InterviewSourceType.PLATFORM_RESUME ? request.resumeVersionId() : null);
            session.setExternalResumeText(request.sourceType() == InterviewSourceType.EXTERNAL_RESUME ? request.externalResumeText().trim() : null);
            session.setJobDescriptionId(request.jobDescriptionId());
            session.setInterviewMode(request.interviewMode());
            session.setOutputLanguage(outputLanguage);
            session.setStatus(InterviewStatus.GENERATING_QUESTION);
            session.setCurrentQuestion(null);
            session.setExecutionMode(ExecutionMode.AI);
            session.setTargetQuestionCount(target);
            session.setMinQuestionCount(minQ);
            session.setMaxQuestionCount(maxQ);
            sessionRepository.saveAndFlush(session);

            // 校验同意
            if (!hasInterviewConsent(userId, session)) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
                        AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("FORBIDDEN");
                attempt.setErrorMessage("需要 AI 面试授权，请先同意隐私政策");
                attempt.setRetryable(false);
                attemptRepository.save(attempt);
                return new StartPreparation(session.getId(), false, attempt.getId());
            }

            // 校验配额
            try {
                checkInterviewQuota(userId);
            } catch (BusinessException e) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
                        AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("RATE_LIMITED");
                attempt.setErrorMessage(e.getMessage());
                attempt.setRetryable(true);
                attemptRepository.save(attempt);
                return new StartPreparation(session.getId(), false, attempt.getId());
            }

            InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
                    AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);

            return new StartPreparation(session.getId(), false, attempt.getId());
        });

        Long sessionId = preparation.sessionId();
        if (preparation.replayed()) {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            return buildStateResponse(session, null,
                    attempt.getStatus() == AiAttemptStatus.FAILED ? buildAiFailure(attempt) : null);
        }

        // TX1 后检查：如果已进入 AI_ACTION_REQUIRED
        InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
        if (afterTx1.getStatus() == InterviewStatus.AI_ACTION_REQUIRED) {
            InterviewAiAttempt failedAttempt = attemptRepository.findAllBySessionId(sessionId).stream()
                    .filter(a -> a.getStatus() == AiAttemptStatus.FAILED)
                    .reduce((a, b) -> b).orElse(null);
            return buildStateResponse(afterTx1, null,
                    failedAttempt != null ? buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION",
                            new BusinessException(
                                    "FORBIDDEN".equals(failedAttempt.getErrorCode()) ? ErrorCode.FORBIDDEN : ErrorCode.RATE_LIMITED,
                                    failedAttempt.getErrorMessage())) : null);
        }

        // Phase 2: 事务外调用 AI
        InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> initialCall;
        try {
            initialCall = callAiForFirstQuestion(afterTx1, userId);
        } catch (BusinessException e) {
            // AI 失败 → TX-error
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                        isRetryable(e), providerRequestId(e));
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt failedAttempt = attemptRepository.findAllBySessionId(sessionId).stream()
                    .filter(a -> a.getStatus() == AiAttemptStatus.FAILED)
                    .reduce((a, b) -> b).orElseThrow();
            return buildStateResponse(failed, null,
                    buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION", e));
        }

        // TX2: 保存 AI 结果
        tx.executeWithoutResult(s -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
            if (session.getStatus() != InterviewStatus.GENERATING_QUESTION) {
                throw new BusinessException(ErrorCode.CONFLICT, "会话状态已变更");
            }
            session.setCurrentQuestion(initialCall.value().getQuestion());
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
            sessionRepository.save(session);

            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            attempt.setStatus(AiAttemptStatus.SUCCESS);
            attempt.setResultJson(Map.of("question", initialCall.value().getQuestion()));
            attempt.setPendingAnswer(null);
            attempt.setProviderRequestId(initialCall.providerRequestId());
            attemptRepository.save(attempt);
        });

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        return buildStateResponse(session, null, null);
    }

    // ==================== 获取会话状态 ====================

    public InterviewStateResponse getState(Long id, Long userId) {
        return tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> notFound("面试会话不存在"));
            if (session.getStatus() == InterviewStatus.GENERATING_QUESTION
                    || session.getStatus() == InterviewStatus.EVALUATING_ANSWER) {
                attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDesc(
                                id, AiAttemptStatus.PROCESSING)
                        .filter(attempt -> isStale(attempt, LocalDateTime.now()))
                        .ifPresent(attempt -> markAttemptFailed(session, attempt,
                                "PROCESSING_TIMEOUT", "AI 请求处理超时，请重试", true, null));
            }
            InterviewStateResponse.AiFailureInfo failure = session.getStatus() == InterviewStatus.AI_ACTION_REQUIRED
                    ? latestFailedAttempt(id).map(this::buildAiFailure).orElse(null) : null;
            return buildStateResponse(session, null, failure);
        });
    }

    // ==================== 提交回答 ====================

    public InterviewStateResponse answer(Long id, String answerText, Long userId, String idempotencyKey) {
        AnswerPreparation preparation = tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> notFound("面试会话不存在"));

            Optional<InterviewAiAttempt> existing = attemptRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                InterviewAiAttempt previous = existing.get();
                String replayFingerprint = buildFingerprint(id, previous.getRoundNo(), answerText);
                if (!Objects.equals(previous.getSessionId(), id)
                        || previous.getOperationType() != AiAttemptOperationType.ANSWER_EVALUATION
                        || !Objects.equals(previous.getRequestFingerprint(), replayFingerprint)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
                }
                if (previous.getStatus() == AiAttemptStatus.PROCESSING
                        && isStale(previous, LocalDateTime.now())) {
                    markAttemptFailed(session, previous, "PROCESSING_TIMEOUT",
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
            if (!hasInterviewConsent(userId, session)) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                long completedCount = recordRepository.countBySessionId(session.getId());
                int roundNo = (int) completedCount + 1;
                String fp = buildFingerprint(session.getId(), roundNo, answerText);
                InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
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
                checkInterviewQuota(userId);
            } catch (BusinessException e) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                long completedCount = recordRepository.countBySessionId(session.getId());
                int roundNo = (int) completedCount + 1;
                String fp = buildFingerprint(session.getId(), roundNo, answerText);
                InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
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
            String fingerprint = buildFingerprint(session.getId(), roundNo, answerText);

            InterviewAiAttempt attempt = createAttempt(userId, session.getId(),
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
                    ? buildAiFailure(attempt) : null;
            return buildStateResponse(session, null, failure);
        }

        // Phase 2: 事务外调用 AI
        InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
        InterviewAiService.AiInvocation<InterviewCoachResponse.AnswerEvaluation> evaluationCall;
        try {
            evaluationCall = interviewAiService.evaluateAnswer(
                    buildEvaluationContext(afterTx1, answerText, userId),
                    afterTx1.getOutputLanguage(),
                    () -> reserveRepairCall(userId, preparation.attemptId()));
            validateEvaluationProgress(afterTx1, roundNo, evaluationCall.value(),
                    evaluationCall.providerRequestId());
        } catch (BusinessException e) {
            // AI 失败 → 转入 AI_ACTION_REQUIRED
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                        isRetryable(e), providerRequestId(e));
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt failedAttempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            return buildStateResponse(failed, null, buildAiFailure(failedAttempt));
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
            record.setFeedbackJson(buildAiFeedback(evaluationResult));
            recordRepository.saveAndFlush(record);

            // 更新 attempt
            attempt.setStatus(AiAttemptStatus.SUCCESS);
            attempt.setResultJson(Map.of("roundScore", totalScore));
            attempt.setPendingAnswer(null);
            attempt.setProviderRequestId(evaluationCall.providerRequestId());
            attemptRepository.save(attempt);

            int newCompletedCount = roundNo;

            applyEvaluationOutcome(session, newCompletedCount, evaluationResult);

            sessionRepository.save(session);
        });

        InterviewSession session = sessionRepository.findById(id).orElseThrow();
        return buildStateResponse(session, null, null);
    }

    // ==================== 重试 AI ====================

    public InterviewStateResponse retryAi(Long id, Long userId) {
        // TX1: 校验 + 增加重试计数
        long[] phase1Result = tx.execute(status -> {
            InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                    .orElseThrow(() -> notFound("面试会话不存在"));

            if (session.getStatus() != InterviewStatus.AI_ACTION_REQUIRED) {
                throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许重试");
            }

            InterviewAiAttempt lastFailed = latestFailedAttempt(session.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到失败的操作记录"));

            if (!lastFailed.getRetryable()) {
                boolean consentRestored = "FORBIDDEN".equals(lastFailed.getErrorCode())
                        && hasInterviewConsent(userId, session);
                if (!consentRestored) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "该操作不可重试");
                }
                lastFailed.setRetryable(true);
            }

            // 校验配额
            try {
                checkInterviewQuota(userId);
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
                return buildStateResponse(checkSession, null,
                        buildAiFailure(attemptId,
                                opType == 0 ? "INITIAL_QUESTION" : "ANSWER_EVALUATION",
                                new BusinessException(ErrorCode.RATE_LIMITED, failedAttempt.getErrorMessage())));
            }
        }

        // Phase 2: 事务外调用 AI
        try {
            if (opType == 0) {
                // 首题重试
                var initialCall = callAiForFirstQuestion(checkSession, userId);
                String question = initialCall.value().getQuestion();

                tx.executeWithoutResult(s -> {
                    InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                    InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                    assertRetryStillCurrent(session, attempt, retryGeneration[0],
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
                        buildEvaluationContext(checkSession, pendingAnswer, userId),
                        checkSession.getOutputLanguage(),
                        () -> {
                            reserveRepairCall(userId, attemptId);
                            retryGeneration[0] += 1;
                        });
                var evaluation = evaluationCall.value();
                validateEvaluationProgress(checkSession, roundNo, evaluation,
                        evaluationCall.providerRequestId());

                tx.executeWithoutResult(s -> {
                    InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                    InterviewAiAttempt att = attemptRepository.findById(attemptId).orElseThrow();
                    assertRetryStillCurrent(session, att, retryGeneration[0],
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
                    record.setFeedbackJson(buildAiFeedback(evaluation));
                    recordRepository.saveAndFlush(record);

                    att.setStatus(AiAttemptStatus.SUCCESS);
                    att.setPendingAnswer(null);
                    att.setResultJson(Map.of("roundScore", totalScore));
                    att.setProviderRequestId(evaluationCall.providerRequestId());
                    attemptRepository.save(att);

                    applyEvaluationOutcome(session, actualRoundNo, evaluation);
                    sessionRepository.save(session);
                });
            }
        } catch (BusinessException e) {
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId).orElseThrow();
                InterviewAiAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
                InterviewStatus expectedStatus = opType == 0
                        ? InterviewStatus.GENERATING_QUESTION : InterviewStatus.EVALUATING_ANSWER;
                if (isCurrentRetry(session, attempt, retryGeneration[0], expectedStatus)) {
                    markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                            isRetryable(e), providerRequestId(e));
                } else {
                    log.info("Discarded stale interview AI retry result: sessionId={}, attemptId={}, generation={}",
                            id, attemptId, retryGeneration[0]);
                }
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewStateResponse.AiFailureInfo failure = failed.getStatus() == InterviewStatus.AI_ACTION_REQUIRED
                    ? latestFailedAttempt(sessionId).map(this::buildAiFailure).orElse(null) : null;
            return buildStateResponse(failed, null, failure);
        }

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        return buildStateResponse(session, null, null);
    }

    // ==================== 规则降级 ====================

    @Transactional
    public InterviewStateResponse continueWithRules(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("面试会话不存在"));

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
            session.setCurrentQuestion(RULE_FIRST_QUESTION);
        }
        sessionRepository.save(session);
        return buildStateResponse(session, null, null);
    }

    // ==================== 用户主动结束 ====================

    @Transactional
    public InterviewStateResponse finish(Long id, Long userId) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("面试会话不存在"));

        if (session.getStatus() == InterviewStatus.COMPLETED) {
            return buildStateResponse(session, null, null);
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
        return buildStateResponse(session, null, null);
    }

    // ==================== 报告 ====================

    @Transactional(readOnly = true)
    public InterviewReportResponse report(Long id, Long userId) {
        InterviewSession session = owned(id, userId);

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
                sumRel += num(m.get("relevance"));
                sumEvid += num(m.get("evidenceSpecificity"));
                sumStruct += num(m.get("structureClarity"));
                sumRole += num(m.get("roleCompetency"));
                sumAuth += num(m.get("authenticityReflection"));
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
                distinctFeedback(records, "strengths"),
                distinctFeedback(records, "improvements"),
                distinctFeedback(records, "resumeSuggestions"),
                distinctFeedback(records, "expressionSuggestions"),
                dimensionScores,
                session.getTargetQuestionCount(), n,
                session.getCompletionReason(), reportSource,
                aiCount, ruleCount
        );
    }

    // ==================== 规则模式作答 ====================

    @Transactional
    public InterviewStateResponse ruleAnswer(Long id, String answerText, Long userId, String idempotencyKey) {
        InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("面试会话不存在"));

        Optional<InterviewAiAttempt> existing = attemptRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            InterviewAiAttempt previous = existing.get();
            String replayFingerprint = buildFingerprint(id, previous.getRoundNo(), answerText);
            if (!Objects.equals(previous.getSessionId(), id)
                    || previous.getOperationType() != AiAttemptOperationType.ANSWER_EVALUATION
                    || !Objects.equals(previous.getRequestFingerprint(), replayFingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突");
            }
            if (previous.getStatus() == AiAttemptStatus.RULE_FALLBACK
                    && Boolean.TRUE.equals(previous.getResultJson() != null
                    ? previous.getResultJson().get("ruleCompleted") : null)) {
                return buildStateResponse(session, null, null);
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
        String fingerprint = buildFingerprint(session.getId(), roundNo, answerText);
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
                return buildStateResponse(session, null, null);
            }
        } else {
            attempt = createRuleAttempt(userId, session.getId(), roundNo,
                    idempotencyKey, answerText, fingerprint);
        }

        // 规则评分
        int score = ruleScore(answerText);
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
            session.setCurrentQuestion(nextRuleQuestion((int) newCount));
        }

        sessionRepository.save(session);
        return buildStateResponse(session, null, null);
    }

    // ==================== 私有方法 ====================

    private InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> callAiForFirstQuestion(
            InterviewSession session, Long userId) {
        String context = buildFirstQuestionContext(session, userId);
        return interviewAiService.generateFirstQuestion(context, session.getOutputLanguage());
    }

    private String providerRequestId(BusinessException exception) {
        return exception instanceof InterviewAiService.AiInvocationException aiException
                ? aiException.providerRequestId() : null;
    }

    private boolean isRetryable(BusinessException exception) {
        return !(exception instanceof InterviewAiService.AiInvocationException aiException)
                || aiException.retryable();
    }

    private boolean isStale(InterviewAiAttempt attempt, LocalDateTime now) {
        return attempt.getUpdatedAt() != null
                && attempt.getUpdatedAt().isBefore(now.minusSeconds(PROCESSING_TAKEOVER_SECONDS));
    }

    private boolean isCurrentRetry(InterviewSession session, InterviewAiAttempt attempt,
                                   int generation, InterviewStatus expectedStatus) {
        return session.getStatus() == expectedStatus
                && session.getExecutionMode() == ExecutionMode.AI
                && attempt.getStatus() == AiAttemptStatus.PROCESSING
                && Objects.equals(attempt.getAttemptCount(), generation);
    }

    private void assertRetryStillCurrent(InterviewSession session, InterviewAiAttempt attempt,
                                         int generation, InterviewStatus expectedStatus) {
        if (!isCurrentRetry(session, attempt, generation, expectedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 重试结果已失效");
        }
    }

    private void validateEvaluationProgress(InterviewSession session, int completedCount,
                                            InterviewCoachResponse.AnswerEvaluation evaluation,
                                            String providerRequestId) {
        boolean reachesMaximum = completedCount >= session.getMaxQuestionCount();
        boolean mayComplete = completedCount >= session.getMinQuestionCount()
                && evaluation.isInformationComplete();
        if (!reachesMaximum && !mayComplete
                && (evaluation.getNextQuestion() == null
                || evaluation.getNextQuestion().getQuestion() == null
                || evaluation.getNextQuestion().getQuestion().isBlank())) {
            throw new InterviewAiService.AiInvocationException(ErrorCode.AI_FAILURE,
                    "AI 在达到最低题数前未返回下一题", providerRequestId, true);
        }
    }

    private void applyEvaluationOutcome(InterviewSession session, int completedCount,
                                        InterviewCoachResponse.AnswerEvaluation evaluation) {
        if (completedCount >= session.getMaxQuestionCount()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.MAX_QUESTION_LIMIT);
        } else if (completedCount >= session.getMinQuestionCount()
                && evaluation.isInformationComplete()) {
            session.setStatus(InterviewStatus.COMPLETED);
            session.setCurrentQuestion(null);
            session.setCompletionReason(CompletionReason.AI_INFORMATION_COMPLETE);
        } else {
            session.setCurrentQuestion(evaluation.getNextQuestion().getQuestion());
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
        }
    }

    private Map<String, Object> buildAiFeedback(InterviewCoachResponse.AnswerEvaluation evaluation) {
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("dimensionScores", Map.of(
                "relevance", evaluation.getDimensionScores().getRelevance(),
                "evidenceSpecificity", evaluation.getDimensionScores().getEvidenceSpecificity(),
                "structureClarity", evaluation.getDimensionScores().getStructureClarity(),
                "roleCompetency", evaluation.getDimensionScores().getRoleCompetency(),
                "authenticityReflection", evaluation.getDimensionScores().getAuthenticityReflection()
        ));
        feedback.put("strengths", evaluation.getStrengths());
        feedback.put("improvements", evaluation.getImprovements());
        feedback.put("suggestedAnswer", evaluation.getSuggestedAnswer());
        feedback.put("resumeSuggestions", evaluation.getResumeSuggestions() != null
                ? evaluation.getResumeSuggestions() : List.of());
        feedback.put("expressionSuggestions", evaluation.getExpressionSuggestions() != null
                ? evaluation.getExpressionSuggestions() : List.of());
        feedback.put("evidenceQuotes", evaluation.getEvidenceQuotes() != null
                ? evaluation.getEvidenceQuotes() : List.of());
        feedback.put("coverageTags", evaluation.getCoverageTags() != null
                ? evaluation.getCoverageTags() : List.of());
        return feedback;
    }

    private AnswerOutcome replayOutcome(InterviewAiAttempt attempt) {
        return switch (attempt.getStatus()) {
            case FAILED -> AnswerOutcome.FAILURE;
            case PROCESSING -> AnswerOutcome.PROCESSING;
            case SUCCESS, RULE_FALLBACK -> AnswerOutcome.SUCCESS;
        };
    }

    private void markAttemptFailed(InterviewSession session, InterviewAiAttempt attempt,
                                   String errorCode, String errorMessage, boolean retryable,
                                   String providerRequestId) {
        session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
        sessionRepository.save(session);
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setErrorCode(errorCode);
        attempt.setErrorMessage(errorMessage);
        attempt.setRetryable(retryable);
        attempt.setProviderRequestId(providerRequestId);
        attemptRepository.save(attempt);
    }

    private InterviewStateResponse.AiFailureInfo buildAiFailure(InterviewAiAttempt attempt) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(attempt.getId());
        info.setStage(attempt.getOperationType().name());
        info.setRetryable(Boolean.TRUE.equals(attempt.getRetryable()));
        info.setReauthorizationRequired("FORBIDDEN".equals(attempt.getErrorCode()));
        info.setMessageCode(attempt.getErrorCode() != null ? attempt.getErrorCode() : ErrorCode.AI_FAILURE.name());
        return info;
    }

    private Optional<InterviewAiAttempt> latestFailedAttempt(Long sessionId) {
        return attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDesc(
                sessionId, AiAttemptStatus.FAILED);
    }

    private enum AnswerOutcome { PROCEED, SUCCESS, FAILURE, PROCESSING }

    private record StartPreparation(Long sessionId, boolean replayed, Long attemptId) {}

    private record AnswerPreparation(Long sessionId, int roundNo,
                                     AnswerOutcome outcome, Long attemptId) {}

    private String buildFirstQuestionContext(InterviewSession session, Long userId) {
        StringBuilder ctx = new StringBuilder();

        // JD — 加载真实内容
        if (session.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(session.getJobDescriptionId(), userId).ifPresentOrElse(
                    jd -> ctx.append("Job Description:\n").append(sanitizer.truncateJdText(jd.getJdText())).append("\n\n"),
                    () -> ctx.append("Job Description: None (general interview)\n\n")
            );
        } else {
            ctx.append("Job Description: None (general interview)\n\n");
        }

        appendResumeContext(ctx, session, userId);

        ctx.append("Interview Mode: ").append(session.getInterviewMode()).append("\n");

        return ctx.toString();
    }

    private String buildEvaluationContext(InterviewSession session, String answer, Long userId) {
        StringBuilder ctx = new StringBuilder();

        if (session.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(session.getJobDescriptionId(), userId).ifPresent(
                    jd -> ctx.append("Job Description:\n").append(sanitizer.truncateJdText(jd.getJdText())).append("\n\n")
            );
        }

        appendResumeContext(ctx, session, userId);

        long completedCount = recordRepository.countBySessionId(session.getId());
        ctx.append("Interview Progress:\n")
                .append("completedQuestionCount: ").append(completedCount).append('\n')
                .append("minQuestionCount: ").append(session.getMinQuestionCount()).append('\n')
                .append("targetQuestionCount: ").append(session.getTargetQuestionCount()).append('\n')
                .append("maxQuestionCount: ").append(session.getMaxQuestionCount()).append("\n\n");

        // 历史问答
        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<Map<String, Object>> recordMaps = new ArrayList<>();
        for (InterviewRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionText", r.getQuestionText());
            m.put("answerText", r.getAnswerText());
            m.put("roundScore", r.getRoundScore());
            m.put("coverageTags", r.getFeedbackJson().getOrDefault("coverageTags", List.of()));
            recordMaps.add(m);
        }

        ctx.append("Conversation History:\n");
        ctx.append(sanitizer.buildHistoryContext(recordMaps));

        ctx.append("\nCurrent Question:\n").append(session.getCurrentQuestion()).append("\n");
        ctx.append("\nCurrent Answer:\n").append(sanitizer.truncateCurrentAnswer(answer)).append("\n");
        ctx.append(sanitizer.untrustedDataMarker());

        return ctx.toString();
    }

    private void appendResumeContext(StringBuilder ctx, InterviewSession session, Long userId) {
        ctx.append("Resume:\n");
        if (session.getSourceType() == InterviewSourceType.PLATFORM_RESUME
                && session.getResumeVersionId() != null) {
            ResumeVersion version = findOwnedResumeVersion(session.getResumeVersionId(), userId);
            Map<String, Object> resumeJson = version.getResumeJson();
            if (resumeJson == null) {
                ctx.append("[empty resume]\n\n");
                return;
            }
            Object summary = sanitizer.sanitizePlatformResume(resumeJson).get("resumeSummary");
            ctx.append(summary != null ? summary.toString() : "[empty resume]").append("\n\n");
        } else if (session.getExternalResumeText() != null) {
            ctx.append(sanitizer.sanitizeExternalResume(session.getExternalResumeText())).append("\n\n");
        } else {
            ctx.append("[empty resume]\n\n");
        }
    }

    private InterviewAiAttempt createAttempt(Long userId, Long sessionId,
                                              AiAttemptOperationType opType, Integer roundNo,
                                              String idempotencyKey, String pendingAnswer,
                                              String fingerprint) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUserId(userId);
        attempt.setSessionId(sessionId);
        attempt.setOperationType(opType);
        attempt.setRoundNo(roundNo);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setPendingAnswer(pendingAnswer);
        attempt.setAttemptCount(1);

        var provider = providerRegistry.route(AiTaskType.INTERVIEW_COACH);
        attempt.setProviderCode(provider.code());
        attempt.setModelCode(provider.modelCode());
        attempt.setPromptVersion(interviewAiService.promptVersion());

        return attemptRepository.save(attempt);
    }

    private InterviewAiAttempt createRuleAttempt(Long userId, Long sessionId, Integer roundNo,
                                                  String idempotencyKey, String answer,
                                                  String fingerprint) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUserId(userId);
        attempt.setSessionId(sessionId);
        attempt.setOperationType(AiAttemptOperationType.ANSWER_EVALUATION);
        attempt.setRoundNo(roundNo);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setPendingAnswer(answer);
        attempt.setAttemptCount(0);
        attempt.setPromptVersion("rule-v1");
        return attemptRepository.save(attempt);
    }

    private String buildFingerprint(Long sessionId, Integer roundNo, String answer) {
        return secureFingerprint(sessionId, roundNo, answer);
    }

    private String buildStartFingerprint(Long userId, StartInterviewRequest request) {
        return secureFingerprint("start", userId,
                request.sourceType(), request.resumeVersionId(),
                request.externalResumeText(), request.jobDescriptionId(), request.interviewMode(),
                request.targetQuestionCount(),
                request.outputLanguage() != null ? request.outputLanguage() : InterviewOutputLanguage.ZH_CN);
    }

    private String secureFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "<null>" : value.toString();
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void checkInterviewQuota(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("用户不存在"));
        LocalDateTime startOfToday = java.time.LocalDate.now().atStartOfDay();
        long count = attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(userId, startOfToday);
        if (count >= interviewDailyQuota) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 面试配额已用完（每日 " + interviewDailyQuota + " 次）");
        }
    }

    private void reserveRepairCall(Long userId, Long attemptId) {
        tx.executeWithoutResult(status -> {
            checkInterviewQuota(userId);
            InterviewAiAttempt attempt = attemptRepository.findById(attemptId)
                    .orElseThrow(() -> notFound("AI 操作不存在"));
            if (attempt.getStatus() != AiAttemptStatus.PROCESSING) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI 操作已失效");
            }
            attempt.setAttemptCount(attempt.getAttemptCount() + 1);
            attemptRepository.save(attempt);
        });
    }

    private int ruleScore(String answer) {
        int score = 35;
        if (answer.length() >= 80) score += 15;
        if (answer.length() >= 160) score += 10;
        String lower = answer.toLowerCase(Locale.ROOT);
        long star = List.of("situation", "task", "action", "result", "背景", "任务", "行动", "结果")
                .stream().filter(lower::contains).count();
        score += (int) Math.min(20, star * 5);
        return Math.min(100, score);
    }

    private String nextRuleQuestion(int completedRounds) {
        int idx = completedRounds % RULE_TOPICS.size();
        return RULE_NEXT_TEMPLATE.formatted(RULE_TOPICS.get(idx));
    }

    private InterviewStateResponse buildStateResponse(InterviewSession session,
                                                       InterviewStateResponse.LastEvaluation lastEval,
                                                       InterviewStateResponse.AiFailureInfo aiFailure) {
        long count = recordRepository.countBySessionId(session.getId());
        Integer currentQNo = count > 0 ? (int) count + 1 : null;

        // 自动加载最近一轮评估
        if (lastEval == null && count > 0) {
            List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
            if (!records.isEmpty()) {
                InterviewRecord latest = records.get(records.size() - 1);
                lastEval = buildLastEvaluation(latest);
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
    private InterviewStateResponse.LastEvaluation buildLastEvaluation(InterviewRecord record) {
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

    private boolean hasInterviewConsent(Long userId, InterviewSession session) {
        List<String> categories = new ArrayList<>(List.of("RESUME", "INTERVIEW_ANSWER"));
        if (session.getJobDescriptionId() != null) {
            categories.add("JOB_DESCRIPTION");
        }
        return consentService.hasValidConsent(userId, "INTERVIEW_COACH", categories);
    }

    private InterviewStateResponse.AiFailureInfo buildAiFailure(Long operationId, String stage, BusinessException e) {
        InterviewStateResponse.AiFailureInfo info = new InterviewStateResponse.AiFailureInfo();
        info.setOperationId(operationId);
        info.setStage(stage);
        info.setRetryable(e.getErrorCode() != ErrorCode.FORBIDDEN);
        info.setReauthorizationRequired(e.getErrorCode() == ErrorCode.FORBIDDEN);
        info.setMessageCode(e.getErrorCode().name());
        return info;
    }

    private void validateSource(StartInterviewRequest request, Long userId) {
        if (request.sourceType() == InterviewSourceType.PLATFORM_RESUME) {
            if (request.resumeVersionId() == null) throw validation("平台简历来源必须选择简历版本");
            findOwnedResumeVersion(request.resumeVersionId(), userId);
        } else if (request.externalResumeText() == null || request.externalResumeText().isBlank()) {
            throw validation("外部简历来源必须提供简历文本");
        }
        if (request.jobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                    .orElseThrow(() -> notFound("岗位不存在"));
        }
    }

    private ResumeVersion findOwnedResumeVersion(Long versionId, Long userId) {
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> notFound("简历版本不存在"));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> notFound("简历版本不存在"));
        return version;
    }

    @SuppressWarnings("unchecked")
    private List<String> distinctFeedback(List<InterviewRecord> records, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (InterviewRecord record : records) {
            Object raw = record.getFeedbackJson().get(key);
            if (raw instanceof Collection<?> items) {
                items.stream().filter(String.class::isInstance).map(String.class::cast).forEach(values::add);
            }
        }
        return values.stream().limit(5).toList();
    }

    private int num(Object val) {
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    private InterviewSession owned(Long id, Long userId) {
        return sessionRepository.findByIdAndUserId(id, userId).orElseThrow(() -> notFound("面试会话不存在"));
    }

    private BusinessException notFound(String message) { return new BusinessException(ErrorCode.NOT_FOUND, message); }
    private BusinessException validation(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
}
