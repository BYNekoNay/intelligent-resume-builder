package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 面试开始流程：两阶段短事务 + 事务外首题 AI。
 *
 * <p>TX1 创建会话并校验同意/配额/幂等 → 事务外调用首题 AI → TX2 落库。
 */
@Service
public class InterviewStartService {

    private static final int DEFAULT_TARGET = 6;
    private static final int MIN_TARGET = 4;
    private static final int MAX_TARGET = 12;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final TransactionTemplate tx;
    private final InterviewStateAssembler stateAssembler;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final InterviewOperationSupport operationSupport;

    public InterviewStartService(InterviewSessionRepository sessionRepository,
                                 InterviewAiAttemptRepository attemptRepository,
                                 TransactionTemplate tx,
                                 InterviewStateAssembler stateAssembler,
                                 InterviewPromptContextAssembler promptContextAssembler,
                                 InterviewOperationSupport operationSupport) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.tx = tx;
        this.stateAssembler = stateAssembler;
        this.promptContextAssembler = promptContextAssembler;
        this.operationSupport = operationSupport;
    }

    private record StartPreparation(Long sessionId, boolean replayed, Long attemptId) {}

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
        promptContextAssembler.validateSource(request, userId);

        // 构建请求指纹
        String fingerprint = operationSupport.buildStartFingerprint(userId, request);

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
                        && operationSupport.isStale(prev, LocalDateTime.now())) {
                    operationSupport.markAttemptFailed(previousSession, prev, "PROCESSING_TIMEOUT",
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
            if (!operationSupport.hasInterviewConsent(userId, session)) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
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
                operationSupport.checkInterviewQuota(userId);
            } catch (BusinessException e) {
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                        AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);
                attempt.setStatus(AiAttemptStatus.FAILED);
                attempt.setAttemptCount(0);
                attempt.setErrorCode("RATE_LIMITED");
                attempt.setErrorMessage(e.getMessage());
                attempt.setRetryable(true);
                attemptRepository.save(attempt);
                return new StartPreparation(session.getId(), false, attempt.getId());
            }

            InterviewAiAttempt attempt = operationSupport.createAttempt(userId, session.getId(),
                    AiAttemptOperationType.INITIAL_QUESTION, null, idempotencyKey, null, fingerprint);

            return new StartPreparation(session.getId(), false, attempt.getId());
        });

        Long sessionId = preparation.sessionId();
        if (preparation.replayed()) {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
            return stateAssembler.buildStateResponse(session, null,
                    attempt.getStatus() == AiAttemptStatus.FAILED ? stateAssembler.buildAiFailure(attempt) : null);
        }

        // TX1 后检查：如果已进入 AI_ACTION_REQUIRED
        InterviewSession afterTx1 = sessionRepository.findById(sessionId).orElseThrow();
        if (afterTx1.getStatus() == InterviewStatus.AI_ACTION_REQUIRED) {
            InterviewAiAttempt failedAttempt = attemptRepository
                    .findTopBySessionIdAndStatusOrderByIdDesc(sessionId, AiAttemptStatus.FAILED).orElse(null);
            return stateAssembler.buildStateResponse(afterTx1, null,
                    failedAttempt != null ? stateAssembler.buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION",
                            new BusinessException(
                                    "FORBIDDEN".equals(failedAttempt.getErrorCode()) ? ErrorCode.FORBIDDEN : ErrorCode.RATE_LIMITED,
                                    failedAttempt.getErrorMessage())) : null);
        }

        // Phase 2: 事务外调用 AI
        InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> initialCall;
        try {
            initialCall = operationSupport.callAiForFirstQuestion(afterTx1, userId);
        } catch (BusinessException e) {
            // AI 失败 → TX-error
            tx.executeWithoutResult(s -> {
                InterviewSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId).orElseThrow();
                session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
                sessionRepository.save(session);
                InterviewAiAttempt attempt = attemptRepository.findById(preparation.attemptId()).orElseThrow();
                operationSupport.markAttemptFailed(session, attempt, e.getErrorCode().name(), e.getMessage(),
                        operationSupport.isRetryable(e), operationSupport.providerRequestId(e));
            });
            InterviewSession failed = sessionRepository.findById(sessionId).orElseThrow();
            InterviewAiAttempt failedAttempt = attemptRepository
                    .findTopBySessionIdAndStatusOrderByIdDesc(sessionId, AiAttemptStatus.FAILED).orElseThrow();
            return stateAssembler.buildStateResponse(failed, null,
                    stateAssembler.buildAiFailure(failedAttempt.getId(), "INITIAL_QUESTION", e));
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
        return stateAssembler.buildStateResponse(session, null, null);
    }
}
