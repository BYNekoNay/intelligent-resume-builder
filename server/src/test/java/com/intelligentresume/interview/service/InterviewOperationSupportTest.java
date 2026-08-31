package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 操作生命周期单测：指纹、配额、reserveRepairCall、stale/retry、attempt 创建、同意。
 */
class InterviewOperationSupportTest {

    private static final Long USER_ID = 7L;
    private static final int QUOTA = 60;

    private InterviewSessionRepository sessionRepository;
    private InterviewAiAttemptRepository attemptRepository;
    private AiProviderRegistry providerRegistry;
    private InterviewAiService interviewAiService;
    private UserRepository userRepository;
    private TransactionTemplate tx;
    private AiConsentService consentService;
    private InterviewPromptContextAssembler promptContextAssembler;
    private InterviewOperationSupport support;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        attemptRepository = mock(InterviewAiAttemptRepository.class);
        providerRegistry = mock(AiProviderRegistry.class);
        interviewAiService = mock(InterviewAiService.class);
        userRepository = mock(UserRepository.class);
        tx = mock(TransactionTemplate.class);
        consentService = mock(AiConsentService.class);
        promptContextAssembler = mock(InterviewPromptContextAssembler.class);
        support = new InterviewOperationSupport(sessionRepository, attemptRepository, providerRegistry,
                interviewAiService, userRepository, tx, consentService, promptContextAssembler, QUOTA);
    }

    // ---- 指纹 ----

    @Test
    @DisplayName("指纹：相同输入结果确定，不同输入可区分")
    void fingerprint_deterministicAndDistinct() {
        String a1 = support.buildFingerprint(1L, 1, "answer");
        String a2 = support.buildFingerprint(1L, 1, "answer");
        String b = support.buildFingerprint(1L, 1, "different");

        assertEquals(a1, a2);
        assertNotEquals(a1, b);
        assertEquals(64, a1.length()); // SHA-256 hex
    }

    @Test
    @DisplayName("指纹：null 值安全处理")
    void fingerprint_handlesNull() {
        String f1 = support.secureFingerprint(1L, null, "x");
        String f2 = support.secureFingerprint(1L, null, "x");

        assertEquals(f1, f2);
        assertNotEquals(f1, support.secureFingerprint(1L, 2L, "x"));
    }

    @Test
    @DisplayName("起始指纹：相同请求参数结果确定")
    void buildStartFingerprint_deterministic() {
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.EXTERNAL_RESUME, null, "ext", 10L, InterviewMode.COMPREHENSIVE, 6, null);

        String f1 = support.buildStartFingerprint(USER_ID, request);
        String f2 = support.buildStartFingerprint(USER_ID, request);

        assertEquals(f1, f2);
    }

    // ---- stale / retry ----

    @Test
    @DisplayName("isStale：超时 75 秒判定")
    void isStale_timeoutWindow() {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUpdatedAt(LocalDateTime.now().minusSeconds(100));
        assertTrue(support.isStale(attempt, LocalDateTime.now()));

        attempt.setUpdatedAt(LocalDateTime.now().minusSeconds(10));
        assertFalse(support.isStale(attempt, LocalDateTime.now()));

        attempt.setUpdatedAt(null);
        assertFalse(support.isStale(attempt, LocalDateTime.now()));
    }

    @Test
    @DisplayName("isCurrentRetry：状态/模式/attemptCount 全部匹配才算当前重试")
    void isCurrentRetry_requiresAllConditions() {
        InterviewSession session = new InterviewSession();
        session.setStatus(InterviewStatus.EVALUATING_ANSWER);
        session.setExecutionMode(ExecutionMode.AI);
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setAttemptCount(3);

        assertTrue(support.isCurrentRetry(session, attempt, 3, InterviewStatus.EVALUATING_ANSWER));

        session.setStatus(InterviewStatus.GENERATING_QUESTION);
        assertFalse(support.isCurrentRetry(session, attempt, 3, InterviewStatus.EVALUATING_ANSWER));

        session.setStatus(InterviewStatus.EVALUATING_ANSWER);
        attempt.setStatus(AiAttemptStatus.SUCCESS);
        assertFalse(support.isCurrentRetry(session, attempt, 3, InterviewStatus.EVALUATING_ANSWER));

        attempt.setStatus(AiAttemptStatus.PROCESSING);
        assertFalse(support.isCurrentRetry(session, attempt, 4, InterviewStatus.EVALUATING_ANSWER));
    }

    @Test
    @DisplayName("assertRetryStillCurrent：不匹配抛 40901")
    void assertRetryStillCurrent_throwsWhenStale() {
        InterviewSession session = new InterviewSession();
        session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
        session.setExecutionMode(ExecutionMode.AI);
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setAttemptCount(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> support.assertRetryStillCurrent(session, attempt, 1, InterviewStatus.GENERATING_QUESTION));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("AI 重试结果已失效", ex.getMessage());
    }

    // ---- 配额 ----

    @Test
    @DisplayName("配额：用户不存在抛 40401")
    void checkInterviewQuota_userMissing() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> support.checkInterviewQuota(USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("配额：达到每日上限抛 42901")
    void checkInterviewQuota_exceeded() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(60L);

        BusinessException ex = assertThrows(BusinessException.class, () -> support.checkInterviewQuota(USER_ID));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    @Test
    @DisplayName("配额：未达上限通过")
    void checkInterviewQuota_ok() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(59L);

        support.checkInterviewQuota(USER_ID); // 不应抛异常
    }

    // ---- reserveRepairCall ----

    @Test
    @DisplayName("reserveRepairCall：attempt 非 PROCESSING 抛 40901")
    void reserveRepairCall_attemptNotProcessing() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(0L);
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setStatus(AiAttemptStatus.SUCCESS);
        when(attemptRepository.findById(1L)).thenReturn(Optional.of(attempt));
        runTxWithoutResult();

        BusinessException ex = assertThrows(BusinessException.class, () -> support.reserveRepairCall(USER_ID, 1L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("reserveRepairCall：正常增加 attemptCount 并保存")
    void reserveRepairCall_incrementsAttemptCount() {
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(0L);
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        attempt.setAttemptCount(1);
        when(attemptRepository.findById(1L)).thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        runTxWithoutResult();

        support.reserveRepairCall(USER_ID, 1L);

        assertEquals(2, attempt.getAttemptCount());
        verify(attemptRepository).save(attempt);
    }

    // ---- createAttempt ----

    @Test
    @DisplayName("createAttempt：初始化字段并写入 provider/model/promptVersion")
    void createAttempt_setsAllFields() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.code()).thenReturn("mock");
        when(provider.modelCode()).thenReturn("mock-1");
        when(providerRegistry.route(AiTaskType.INTERVIEW_COACH)).thenReturn(provider);
        when(interviewAiService.promptVersion()).thenReturn("v11");
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InterviewAiAttempt attempt = support.createAttempt(USER_ID, 1L,
                AiAttemptOperationType.ANSWER_EVALUATION, 2, "key", "pending-answer", "fp");

        assertEquals(USER_ID, attempt.getUserId());
        assertEquals(1L, attempt.getSessionId());
        assertEquals(AiAttemptOperationType.ANSWER_EVALUATION, attempt.getOperationType());
        assertEquals(2, attempt.getRoundNo());
        assertEquals("key", attempt.getIdempotencyKey());
        assertEquals("fp", attempt.getRequestFingerprint());
        assertEquals(AiAttemptStatus.PROCESSING, attempt.getStatus());
        assertEquals("pending-answer", attempt.getPendingAnswer());
        assertEquals(1, attempt.getAttemptCount());
        assertEquals("mock", attempt.getProviderCode());
        assertEquals("mock-1", attempt.getModelCode());
        assertEquals("v11", attempt.getPromptVersion());
    }

    @Test
    @DisplayName("createRuleAttempt：规则 attempt 使用 rule-v1 提示版本")
    void createRuleAttempt_setsRuleVersion() {
        when(attemptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InterviewAiAttempt attempt = support.createRuleAttempt(USER_ID, 1L, 1, "key", "answer", "fp");

        assertEquals("rule-v1", attempt.getPromptVersion());
        assertEquals(0, attempt.getAttemptCount());
        assertEquals(AiAttemptOperationType.ANSWER_EVALUATION, attempt.getOperationType());
    }

    // ---- 同意 ----

    @Test
    @DisplayName("hasInterviewConsent：无 JD 时不请求 JOB_DESCRIPTION 类别")
    void hasInterviewConsent_withoutJd() {
        when(consentService.hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"), any())).thenReturn(true);
        InterviewSession session = new InterviewSession();
        session.setJobDescriptionId(null);

        assertTrue(support.hasInterviewConsent(USER_ID, session));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(consentService).hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"), captor.capture());
        assertEquals(List.of("RESUME", "INTERVIEW_ANSWER"), captor.getValue());
    }

    @Test
    @DisplayName("hasInterviewConsent：有 JD 时追加 JOB_DESCRIPTION 类别")
    void hasInterviewConsent_withJd() {
        when(consentService.hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"), any())).thenReturn(false);
        InterviewSession session = new InterviewSession();
        session.setJobDescriptionId(10L);

        assertFalse(support.hasInterviewConsent(USER_ID, session));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(consentService).hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"), captor.capture());
        assertEquals(List.of("RESUME", "INTERVIEW_ANSWER", "JOB_DESCRIPTION"), captor.getValue());
    }

    // ---- 评估结果应用 ----

    @Test
    @DisplayName("applyEvaluationOutcome：达到最大题数 → COMPLETED/MAX_QUESTION_LIMIT")
    void applyEvaluationOutcome_maxReached() {
        InterviewSession session = sessionWithCounts(9, 3, 9);
        InterviewCoachResponse.AnswerEvaluation evaluation = evaluation(false, null);

        support.applyEvaluationOutcome(session, 9, evaluation);

        assertEquals(InterviewStatus.COMPLETED, session.getStatus());
        assertEquals(CompletionReason.MAX_QUESTION_LIMIT, session.getCompletionReason());
        assertNull(session.getCurrentQuestion());
    }

    @Test
    @DisplayName("applyEvaluationOutcome：信息完整 → COMPLETED/AI_INFORMATION_COMPLETE")
    void applyEvaluationOutcome_informationComplete() {
        InterviewSession session = sessionWithCounts(6, 3, 9);
        InterviewCoachResponse.AnswerEvaluation evaluation = evaluation(true, "next");

        support.applyEvaluationOutcome(session, 3, evaluation);

        assertEquals(InterviewStatus.COMPLETED, session.getStatus());
        assertEquals(CompletionReason.AI_INFORMATION_COMPLETE, session.getCompletionReason());
    }

    @Test
    @DisplayName("applyEvaluationOutcome：未完成 → AWAITING_ANSWER + 下一题")
    void applyEvaluationOutcome_continues() {
        InterviewSession session = sessionWithCounts(6, 3, 9);
        session.setCurrentQuestion("old");
        InterviewCoachResponse.AnswerEvaluation evaluation = evaluation(false, "next-question");

        support.applyEvaluationOutcome(session, 1, evaluation);

        assertEquals(InterviewStatus.AWAITING_ANSWER, session.getStatus());
        assertEquals("next-question", session.getCurrentQuestion());
        assertNull(session.getCompletionReason());
    }

    @Test
    @DisplayName("validateEvaluationProgress：未达最低题数且无下一题抛 AI_FAILURE")
    void validateEvaluationProgress_missingNextQuestion() {
        InterviewSession session = sessionWithCounts(6, 3, 9);
        InterviewCoachResponse.AnswerEvaluation evaluation = evaluation(false, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> support.validateEvaluationProgress(session, 1, evaluation, "req-1"));

        assertEquals(ErrorCode.AI_FAILURE, ex.getErrorCode());
        assertTrue(ex instanceof InterviewAiService.AiInvocationException);
    }

    @Test
    @DisplayName("buildAiFeedback：映射五维分数与列表字段")
    void buildAiFeedback_mapsAllFields() {
        InterviewCoachResponse.AnswerEvaluation evaluation = new InterviewCoachResponse.AnswerEvaluation();
        InterviewCoachResponse.DimensionScores dims = new InterviewCoachResponse.DimensionScores();
        dims.setRelevance(10);
        dims.setEvidenceSpecificity(8);
        dims.setStructureClarity(6);
        dims.setRoleCompetency(5);
        dims.setAuthenticityReflection(2);
        evaluation.setDimensionScores(dims);
        evaluation.setStrengths(List.of("s"));
        evaluation.setImprovements(List.of("i"));
        evaluation.setSuggestedAnswer("sa");
        evaluation.setResumeSuggestions(null);
        evaluation.setExpressionSuggestions(List.of("es"));
        evaluation.setEvidenceQuotes(List.of("eq"));
        evaluation.setCoverageTags(null);

        Map<String, Object> feedback = support.buildAiFeedback(evaluation);

        assertEquals(10, ((Map<?, ?>) feedback.get("dimensionScores")).get("relevance"));
        assertEquals(List.of("s"), feedback.get("strengths"));
        assertEquals(List.of("es"), feedback.get("expressionSuggestions"));
        assertEquals(List.of(), feedback.get("resumeSuggestions"));
        assertEquals(List.of(), feedback.get("coverageTags"));
    }

    @Test
    @DisplayName("markAttemptFailed：会话转 AI_ACTION_REQUIRED，attempt 标记失败")
    void markAttemptFailed_marksSessionAndAttempt() {
        InterviewSession session = new InterviewSession();
        session.setStatus(InterviewStatus.GENERATING_QUESTION);
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setStatus(AiAttemptStatus.PROCESSING);
        when(sessionRepository.save(session)).thenReturn(session);
        when(attemptRepository.save(attempt)).thenReturn(attempt);

        support.markAttemptFailed(session, attempt, "AI_FAILURE", "boom", true, "req-1");

        assertEquals(InterviewStatus.AI_ACTION_REQUIRED, session.getStatus());
        assertEquals(AiAttemptStatus.FAILED, attempt.getStatus());
        assertEquals("AI_FAILURE", attempt.getErrorCode());
        assertEquals("boom", attempt.getErrorMessage());
        assertTrue(attempt.getRetryable());
        assertEquals("req-1", attempt.getProviderRequestId());
        verify(sessionRepository).save(session);
        verify(attemptRepository).save(attempt);
    }

    // ---- 帮助方法 ----

    private InterviewSession sessionWithCounts(int target, int min, int max) {
        InterviewSession session = new InterviewSession();
        session.setStatus(InterviewStatus.EVALUATING_ANSWER);
        session.setExecutionMode(ExecutionMode.AI);
        session.setTargetQuestionCount(target);
        session.setMinQuestionCount(min);
        session.setMaxQuestionCount(max);
        return session;
    }

    private InterviewCoachResponse.AnswerEvaluation evaluation(boolean complete, String nextQuestion) {
        InterviewCoachResponse.AnswerEvaluation evaluation = new InterviewCoachResponse.AnswerEvaluation();
        evaluation.setInformationComplete(complete);
        if (nextQuestion != null) {
            InterviewCoachResponse.NextQuestion next = new InterviewCoachResponse.NextQuestion();
            next.setQuestion(nextQuestion);
            evaluation.setNextQuestion(next);
        }
        return evaluation;
    }

    @SuppressWarnings("unchecked")
    private void runTxWithoutResult() {
        doAnswer(invocation -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0))
                    .accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
    }
}
