package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 操作生命周期支持：attempt 创建、指纹幂等、配额/同意校验、
 * stale/retry 判定、失败标记、评估结果应用。
 *
 * <p>被 start/answer/retry/rule 流程共享，禁止反向依赖流程类。
 * 日志只记录 id/状态/错误码，不记录简历/JD/回答内容。
 */
@Component
public class InterviewOperationSupport {

    private static final long PROCESSING_TAKEOVER_SECONDS = 75;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAiAttemptRepository attemptRepository;
    private final AiProviderRegistry providerRegistry;
    private final InterviewAiService interviewAiService;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;
    private final AiConsentService consentService;
    private final InterviewPromptContextAssembler promptContextAssembler;
    private final int interviewDailyQuota;

    public InterviewOperationSupport(InterviewSessionRepository sessionRepository,
                                     InterviewAiAttemptRepository attemptRepository,
                                     AiProviderRegistry providerRegistry,
                                     InterviewAiService interviewAiService,
                                     UserRepository userRepository,
                                     TransactionTemplate tx,
                                     AiConsentService consentService,
                                     InterviewPromptContextAssembler promptContextAssembler,
                                     @Value("${app.ai.quota.INTERVIEW_COACH:60}") int interviewDailyQuota) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.providerRegistry = providerRegistry;
        this.interviewAiService = interviewAiService;
        this.userRepository = userRepository;
        this.tx = tx;
        this.consentService = consentService;
        this.promptContextAssembler = promptContextAssembler;
        this.interviewDailyQuota = interviewDailyQuota;
    }

    public InterviewAiService.AiInvocation<InterviewCoachResponse.InitialQuestion> callAiForFirstQuestion(
            InterviewSession session, Long userId) {
        String context = promptContextAssembler.buildFirstQuestionContext(session, userId);
        return interviewAiService.generateFirstQuestion(context, session.getOutputLanguage());
    }

    public String providerRequestId(BusinessException exception) {
        return exception instanceof InterviewAiService.AiInvocationException aiException
                ? aiException.providerRequestId() : null;
    }

    public boolean isRetryable(BusinessException exception) {
        return !(exception instanceof InterviewAiService.AiInvocationException aiException)
                || aiException.retryable();
    }

    public boolean isStale(InterviewAiAttempt attempt, LocalDateTime now) {
        return attempt.getUpdatedAt() != null
                && attempt.getUpdatedAt().isBefore(now.minusSeconds(PROCESSING_TAKEOVER_SECONDS));
    }

    public boolean isCurrentRetry(InterviewSession session, InterviewAiAttempt attempt,
                                  int generation, InterviewStatus expectedStatus) {
        return session.getStatus() == expectedStatus
                && session.getExecutionMode() == ExecutionMode.AI
                && attempt.getStatus() == AiAttemptStatus.PROCESSING
                && Objects.equals(attempt.getAttemptCount(), generation);
    }

    public void assertRetryStillCurrent(InterviewSession session, InterviewAiAttempt attempt,
                                        int generation, InterviewStatus expectedStatus) {
        if (!isCurrentRetry(session, attempt, generation, expectedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 重试结果已失效");
        }
    }

    public void validateEvaluationProgress(InterviewSession session, int completedCount,
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

    public void applyEvaluationOutcome(InterviewSession session, int completedCount,
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

    public Map<String, Object> buildAiFeedback(InterviewCoachResponse.AnswerEvaluation evaluation) {
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

    public void markAttemptFailed(InterviewSession session, InterviewAiAttempt attempt,
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

    public InterviewAiAttempt createAttempt(Long userId, Long sessionId,
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

    public InterviewAiAttempt createRuleAttempt(Long userId, Long sessionId, Integer roundNo,
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

    public String buildFingerprint(Long sessionId, Integer roundNo, String answer) {
        return secureFingerprint(sessionId, roundNo, answer);
    }

    public String buildStartFingerprint(Long userId, StartInterviewRequest request) {
        return secureFingerprint("start", userId,
                request.sourceType(), request.resumeVersionId(),
                request.externalResumeText(), request.jobDescriptionId(), request.interviewMode(),
                request.targetQuestionCount(),
                request.outputLanguage() != null ? request.outputLanguage() : InterviewOutputLanguage.ZH_CN);
    }

    public String secureFingerprint(Object... values) {
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

    public void checkInterviewQuota(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("用户不存在"));
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long count = attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(userId, startOfToday);
        if (count >= interviewDailyQuota) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 面试配额已用完（每日 " + interviewDailyQuota + " 次）");
        }
    }

    public void reserveRepairCall(Long userId, Long attemptId) {
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

    public boolean hasInterviewConsent(Long userId, InterviewSession session) {
        List<String> categories = new ArrayList<>(List.of("RESUME", "INTERVIEW_ANSWER"));
        if (session.getJobDescriptionId() != null) {
            categories.add("JOB_DESCRIPTION");
        }
        return consentService.hasValidConsent(userId, "INTERVIEW_COACH", categories);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}
