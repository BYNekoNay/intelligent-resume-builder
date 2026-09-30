package com.intelligentresume.interview.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 重试流程单测。
 *
 * <p><b>本类要防的缺陷</b>：{@code POST /api/interviews/{id}/ai/retry} 原先在请求线程内
 * **同步等待同一次 AI 评估**（与 {@code /answer} 修复前完全同源，实测平均 108.7s），
 * 而前端该接口超时只有 60s（`web/src/api/interview.ts`）：用户点「重试」后必然超时，
 * 服务端仍在评估、随后把结果落到会话上——重试按钮形同虚设。
 * {@code /answer} 已改为「秒回 {@code EVALUATING_ANSWER} + 轮询取终态」，重试却漏改。
 *
 * <p>核心断言：{@code retryAi()} 在 **AI 被调用之前**就返回，状态为 PROCESSING
 * （{@code EVALUATING_ANSWER} / {@code GENERATING_QUESTION}），由注入的执行器驱动。
 */
class InterviewRetryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 1L;
    private static final Long ATTEMPT_ID = 100L;

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewAiAttemptRepository attemptRepository;
    private InterviewAiService interviewAiService;
    private TransactionTemplate tx;
    private InterviewOperationSupport operationSupport;

    /** 可控执行器：把待执行任务存起来，由测试显式触发。 */
    private final List<Runnable> pending = new ArrayList<>();
    private final Executor manualExecutor = pending::add;

    private InterviewRetryService service;
    private InterviewSession session;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        attemptRepository = mock(InterviewAiAttemptRepository.class);
        interviewAiService = mock(InterviewAiService.class);
        tx = mock(TransactionTemplate.class);
        operationSupport = mock(InterviewOperationSupport.class);

        session = new InterviewSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
        session.setExecutionMode(ExecutionMode.AI);
        session.setOutputLanguage(InterviewOutputLanguage.ZH_CN);
        session.setCurrentQuestion("请介绍一次性能优化经历");
        session.setTargetQuestionCount(4);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);

        // TX1：**必须真正执行回调**，否则「置 PROCESSING / 加计数 / 改会话状态」的副作用不会发生
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

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
        when(recordRepository.countBySessionId(SESSION_ID)).thenReturn(0L);
        when(operationSupport.isCurrentRetry(any(), any(), anyInt(), any())).thenReturn(true);
        // markAttemptFailed 的真实实现会改会话与 attempt 字段；mock 里模拟这些副作用供断言使用
        doAnswer(invocation -> {
            InterviewSession target = invocation.getArgument(0);
            InterviewAiAttempt failed = invocation.getArgument(1);
            target.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
            failed.setStatus(AiAttemptStatus.FAILED);
            failed.setErrorCode(invocation.getArgument(2));
            failed.setErrorMessage(invocation.getArgument(3));
            failed.setRetryable(invocation.getArgument(4));
            return null;
        }).when(operationSupport).markAttemptFailed(any(), any(), anyString(), anyString(),
                anyBoolean(), any());
        // applyEvaluationOutcome 的真实实现会推进状态；这里模拟其「进入下一题」的效果
        doAnswer(invocation -> {
            session.setStatus(InterviewStatus.AWAITING_ANSWER);
            return null;
        }).when(operationSupport).applyEvaluationOutcome(any(), any(Integer.class), any());

        InterviewStateAssembler assembler =
                new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        service = new InterviewRetryService(sessionRepository, recordRepository, attemptRepository,
                interviewAiService, tx, assembler, mock(InterviewPromptContextAssembler.class),
                operationSupport, manualExecutor);
    }

    private InterviewAiAttempt failedAttempt(AiAttemptOperationType type, Integer roundNo, String pendingAnswer) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setSessionId(SESSION_ID);
        attempt.setOperationType(type);
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setRetryable(true);
        attempt.setAttemptCount(1);
        attempt.setRoundNo(roundNo);
        attempt.setPendingAnswer(pendingAnswer);
        attempt.setErrorMessage("上次失败");
        return attempt;
    }

    private void stubFailedAttempt(InterviewAiAttempt attempt) {
        when(attemptRepository.findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc(
                SESSION_ID, AiAttemptStatus.FAILED)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
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
    @DisplayName("回归：retryAi() 在 AI 被调用之前就返回 EVALUATING_ANSWER（原为同步等待 108s）")
    void retryAi_returnsBeforeAiRuns() {
        stubFailedAttempt(failedAttempt(AiAttemptOperationType.ANSWER_EVALUATION, 1, "上次的回答"));
        // 桩上合法返回：使「同步实现」也能走完并暴露状态差异，而不是以 NPE 中断
        when(interviewAiService.evaluateAnswer(any(), any(), any()))
                .thenReturn(new InterviewAiService.AiInvocation<>(evaluation(), "req-1"));

        InterviewStateResponse response = service.retryAi(SESSION_ID, USER_ID);

        assertEquals(InterviewStatus.EVALUATING_ANSWER, response.getStatus(),
                "retryAi() 应立即返回「评估中」，而不是同步等待 AI（原缺陷：前端 60s 超时）");
        verify(interviewAiService, never()).evaluateAnswer(any(), any(), any());
        assertEquals(1, pending.size(), "重试任务应已提交到后台执行器");
    }

    @Test
    @DisplayName("后台执行重试：AI 被调用、回答记录落库、attempt 置成功")
    void backgroundRetry_persistsEvaluation() {
        InterviewAiAttempt attempt = failedAttempt(AiAttemptOperationType.ANSWER_EVALUATION, 1, "上次的回答");
        stubFailedAttempt(attempt);
        when(interviewAiService.evaluateAnswer(any(), any(), any()))
                .thenReturn(new InterviewAiService.AiInvocation<>(evaluation(), "req-1"));

        service.retryAi(SESSION_ID, USER_ID);
        pending.get(0).run();

        verify(interviewAiService).evaluateAnswer(any(), any(), any());
        verify(recordRepository, atLeastOnce()).saveAndFlush(any());
        verify(operationSupport).applyEvaluationOutcome(any(), any(Integer.class), any());
        assertEquals(AiAttemptStatus.SUCCESS, attempt.getStatus());
    }

    @Test
    @DisplayName("首题重试：立即返回 GENERATING_QUESTION，后台写入首题并进入等待回答")
    void initialQuestionRetry_backgroundAdvances() {
        InterviewAiAttempt attempt = failedAttempt(AiAttemptOperationType.INITIAL_QUESTION, null, null);
        stubFailedAttempt(attempt);
        InterviewCoachResponse.InitialQuestion question = new InterviewCoachResponse.InitialQuestion();
        question.setQuestion("请介绍你在订单服务中做过的性能优化");
        when(operationSupport.callAiForFirstQuestion(any(), any()))
                .thenReturn(new InterviewAiService.AiInvocation<>(question, "req-2"));

        InterviewStateResponse response = service.retryAi(SESSION_ID, USER_ID);

        assertEquals(InterviewStatus.GENERATING_QUESTION, response.getStatus());
        assertEquals(1, pending.size(), "首题重试同样应交给后台执行器");

        pending.get(0).run();

        assertEquals(InterviewStatus.AWAITING_ANSWER, session.getStatus());
        assertEquals("请介绍你在订单服务中做过的性能优化", session.getCurrentQuestion());
        assertEquals(AiAttemptStatus.SUCCESS, attempt.getStatus());
    }

    @Test
    @DisplayName("执行器队列满：立即返回可重试失败，不静默滞留")
    void executorRejected_marksRetryableFailure() {
        stubFailedAttempt(failedAttempt(AiAttemptOperationType.ANSWER_EVALUATION, 1, "上次的回答"));
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        InterviewStateAssembler assembler =
                new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        InterviewRetryService rejectingService = new InterviewRetryService(sessionRepository, recordRepository,
                attemptRepository, interviewAiService, tx, assembler,
                mock(InterviewPromptContextAssembler.class), operationSupport, rejecting);

        InterviewStateResponse response = rejectingService.retryAi(SESSION_ID, USER_ID);

        assertEquals(InterviewStatus.AI_ACTION_REQUIRED, session.getStatus());
        assertNotNull(response.getAiFailure(), "队列满时应把失败原因暴露给前端");
        verify(operationSupport).markAttemptFailed(any(), any(), eq("QUEUE_REJECTED"), anyString(),
                eq(true), any());
        verify(interviewAiService, never()).evaluateAnswer(any(), any(), any());
    }
}
