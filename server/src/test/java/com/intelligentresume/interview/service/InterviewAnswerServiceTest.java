package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试答题流程单测。
 *
 * <p><b>本类要防的缺陷</b>：{@code POST /interviews/{id}/answer} 原先在请求线程内同步等待
 * AI 评估，实测平均 108.7s（4 轮 102.3 / 76.4 / 110.0 / 145.9s），而前端该接口超时只有 60s
 * —— 4 轮全部超时，用户提交后看到失败、服务端仍在评估，重试遇 409「当前不在等待回答状态」、
 * 结束遇 409「AI 操作进行中」，卡在无法前进的面试里。
 *
 * <p>因此本测试的**核心断言**是：{@code answer()} 在 **AI 被调用之前**就返回，
 * 且返回状态为 {@code EVALUATING_ANSWER}；评估由注入的执行器驱动，
 * 用可控执行器（把任务存起来不立刻跑）即可确定性验证这一点。
 */
class InterviewAnswerServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 1L;
    private static final Long ATTEMPT_ID = 100L;
    private static final String IDEM_KEY = "answer-key-1";
    private static final String ANSWER_TEXT = "我在订单服务中引入缓存与慢查询治理，把 P99 降低了约三分之一。";

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewAiAttemptRepository attemptRepository;
    private InterviewAiService interviewAiService;
    private TransactionTemplate tx;
    private InterviewOperationSupport operationSupport;

    /** 可控执行器：把待执行任务存起来，由测试显式触发。 */
    private final List<Runnable> pending = new ArrayList<>();
    private final Executor manualExecutor = pending::add;

    private InterviewAnswerService service;
    private InterviewSession session;

    @BeforeEach
    void setUp() throws Exception {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        attemptRepository = mock(InterviewAiAttemptRepository.class);
        interviewAiService = mock(InterviewAiService.class);
        tx = mock(TransactionTemplate.class);
        operationSupport = mock(InterviewOperationSupport.class);

        session = new InterviewSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(InterviewStatus.AWAITING_ANSWER);
        session.setExecutionMode(ExecutionMode.AI);
        session.setOutputLanguage(InterviewOutputLanguage.ZH_CN);
        session.setCurrentQuestion("请介绍一次性能优化经历");
        session.setTargetQuestionCount(4);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);

        // TX1：**必须真正执行回调**，否则 TX1 里"置 EVALUATING_ANSWER / 创建 attempt"的副作用不会发生
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(tx).execute(any());

        // TX2 / 失败标记：直接执行传入的 Consumer
        doAnswer(invocation -> {
            java.util.function.Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setSessionId(SESSION_ID);
        attempt.setOperationType(AiAttemptOperationType.ANSWER_EVALUATION);
        attempt.setStatus(AiAttemptStatus.PROCESSING);

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(recordRepository.countBySessionId(SESSION_ID)).thenReturn(0L);
        when(operationSupport.hasInterviewConsent(any(), any())).thenReturn(true);
        when(operationSupport.buildFingerprint(any(), any(), any())).thenReturn("fp");
        when(operationSupport.createAttempt(any(), any(), any(), any(), any(), any(), any())).thenReturn(attempt);
        // markAttemptFailed 的真实实现会改 attempt 字段；mock 里模拟这些副作用供断言使用
        doAnswer(invocation -> {
            InterviewAiAttempt failed = invocation.getArgument(1);
            failed.setStatus(AiAttemptStatus.FAILED);
            failed.setErrorCode(invocation.getArgument(2));
            failed.setErrorMessage(invocation.getArgument(3));
            failed.setRetryable(invocation.getArgument(4));
            return null;
        }).when(operationSupport).markAttemptFailed(any(), any(), anyString(), anyString(),
                anyBoolean(), any());
        // applyEvaluationOutcome 的真实实现会推进状态；这里模拟其"进入下一题"的效果
        doAnswer(invocation -> {
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
            return null;
        }).when(operationSupport).applyEvaluationOutcome(any(), any(Integer.class), any());

        InterviewStateAssembler assembler =
                new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        service = new InterviewAnswerService(sessionRepository, recordRepository, attemptRepository,
                interviewAiService, tx, assembler, mock(InterviewPromptContextAssembler.class),
                operationSupport, manualExecutor);
    }

    private Object newPreparation(long sessionId, int roundNo, String outcome, long attemptId)
            throws Exception {
        Class<?> preparation = Class.forName(
                "com.intelligentresume.interview.service.InterviewAnswerService$AnswerPreparation");
        Class<?> outcomeType = Class.forName(
                "com.intelligentresume.interview.service.InterviewAnswerService$AnswerOutcome");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object outcomeValue = Enum.valueOf((Class<Enum>) outcomeType, outcome);
        Constructor<?> ctor = null;
        for (Constructor<?> candidate : preparation.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 4) {
                ctor = candidate;
                break;
            }
        }
        assertNotNull(ctor, "未找到 AnswerPreparation 的四参构造器");
        ctor.setAccessible(true);
        return ctor.newInstance(sessionId, roundNo, outcomeValue, attemptId);
    }

    private InterviewCoachResponse.AnswerEvaluation evaluation() {
        InterviewCoachResponse.DimensionScores scores = new InterviewCoachResponse.DimensionScores();
        scores.setRelevance(20);
        scores.setEvidenceSpecificity(18);
        scores.setStructureClarity(16);
        scores.setRoleCompetency(15);
        scores.setAuthenticityReflection(8);

        InterviewCoachResponse.AnswerEvaluation evaluation = new InterviewCoachResponse.AnswerEvaluation();
        evaluation.setDimensionScores(scores);
        evaluation.setStrengths(List.of("方向与岗位相关"));
        evaluation.setImprovements(List.of("补充定位瓶颈的方法"));
        evaluation.setSuggestedAnswer("建议补充具体定位手段与量化结果，说明个人贡献。");
        return evaluation;
    }

    @Test
    @DisplayName("回归：answer() 在 AI 被调用之前就返回，且状态为 EVALUATING_ANSWER（原为同步等待 108s）")
    void answer_returnsBeforeAiEvaluation() {
        InterviewStateResponse response = service.answer(SESSION_ID, ANSWER_TEXT, USER_ID, IDEM_KEY);

        // ① 立即返回，状态为"评估中"
        assertEquals(InterviewStatus.EVALUATING_ANSWER, response.getStatus(),
                "answer() 应立即返回 EVALUATING_ANSWER，而不是等 AI 评估完成");
        // ② 此刻 AI 尚未被调用 —— 这正是原缺陷的核心：请求线程不再阻塞在 AI 上
        verify(interviewAiService, never()).evaluateAnswer(any(), any(), any());
        // ③ 评估任务已被排入执行器
        assertEquals(1, pending.size(), "评估任务应已提交到后台执行器");
    }

    @Test
    @DisplayName("后台执行评估后：AI 被调用、回答记录落库、attempt 置成功")
    void backgroundEvaluation_persistsRecord() {
        service.answer(SESSION_ID, ANSWER_TEXT, USER_ID, IDEM_KEY);
        when(interviewAiService.evaluateAnswer(any(), any(), any()))
                .thenReturn(new InterviewAiService.AiInvocation<>(evaluation(), "req-1"));

        pending.get(0).run();

        verify(interviewAiService).evaluateAnswer(any(), any(), any());
        verify(recordRepository, atLeastOnce()).saveAndFlush(any());
        verify(operationSupport).applyEvaluationOutcome(any(), any(Integer.class), any());
    }

    @Test
    @DisplayName("后台评估失败：会话转入 AI_ACTION_REQUIRED，attempt 被标记失败（前端经 aiFailure 看到）")
    void backgroundEvaluationFailure_marksSession() {
        service.answer(SESSION_ID, ANSWER_TEXT, USER_ID, IDEM_KEY);
        when(interviewAiService.evaluateAnswer(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_FAILURE, "模型返回失败"));

        pending.get(0).run();

        assertEquals(InterviewStatus.AI_ACTION_REQUIRED, session.getStatus(),
                "AI 失败后会话应转入 AI_ACTION_REQUIRED 而不是卡在 EVALUATING_ANSWER");
        verify(operationSupport).markAttemptFailed(any(), any(), anyString(), anyString(),
                anyBoolean(), any());
    }

    @Test
    @DisplayName("执行器队列满：立即返回失败且标记为可重试，不静默滞留")
    void executorRejected_marksRetryableFailure() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        InterviewStateAssembler assembler =
                new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        InterviewAnswerService rejectingService = new InterviewAnswerService(sessionRepository,
                recordRepository, attemptRepository, interviewAiService, tx, assembler,
                mock(InterviewPromptContextAssembler.class), operationSupport, rejecting);

        InterviewStateResponse response = rejectingService.answer(SESSION_ID, ANSWER_TEXT, USER_ID, IDEM_KEY);

        assertEquals(InterviewStatus.AI_ACTION_REQUIRED, session.getStatus());
        assertNotNull(response.getAiFailure(), "队列满时应把失败原因暴露给前端");
        verify(operationSupport).markAttemptFailed(any(), any(), eq("QUEUE_REJECTED"), anyString(),
                eq(true), any());
        verify(interviewAiService, never()).evaluateAnswer(any(), any(), any());
    }

    @Test
    @DisplayName("幂等键复用（评估进行中）：直接返回既有状态，不重复调用 AI")
    void idempotentReplay_doesNotCallAiAgain() {
        // 重放发生时，会话应处于评估中（这正是生产里"客户端超时后用同一个键重试"的场景）
        session.setStatus(InterviewStatus.EVALUATING_ANSWER);
        InterviewAiAttempt processing = new InterviewAiAttempt();
        processing.setId(ATTEMPT_ID);
        processing.setSessionId(SESSION_ID);
        processing.setRoundNo(1);
        processing.setOperationType(AiAttemptOperationType.ANSWER_EVALUATION);
        processing.setStatus(AiAttemptStatus.PROCESSING);
        processing.setRequestFingerprint("fp");
        when(attemptRepository.findByUserIdAndIdempotencyKey(any(), any()))
                .thenReturn(Optional.of(processing));
        doAnswer(invocation -> newPreparation(SESSION_ID, 1, "PROCESSING", ATTEMPT_ID))
                .when(tx).execute(any());

        InterviewStateResponse response = service.answer(SESSION_ID, ANSWER_TEXT, USER_ID, IDEM_KEY);

        assertEquals(InterviewStatus.EVALUATING_ANSWER, response.getStatus());
        verify(interviewAiService, never()).evaluateAnswer(any(), any(), any());
        assertTrue(pending.isEmpty(), "重放不应再次提交评估任务");
    }
}
