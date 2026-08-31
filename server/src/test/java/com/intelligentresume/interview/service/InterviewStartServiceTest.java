package com.intelligentresume.interview.service;

import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewCoachResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试开始流程单测：提供 initialQuestion 时跳过 AI 首题生成，直达 AWAITING_ANSWER。
 */
class InterviewStartServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 1L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long RESUME_VERSION_ID = 10L;
    private static final Long JOB_ID = 30L;
    private static final String IDEM_KEY = "start-key-1";

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewAiAttemptRepository attemptRepository;
    private TransactionTemplate tx;
    private InterviewPromptContextAssembler promptContextAssembler;
    private InterviewOperationSupport operationSupport;
    private InterviewStartService service;
    private InterviewSession session;

    @BeforeEach
    void setUp() throws Exception {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        attemptRepository = mock(InterviewAiAttemptRepository.class);
        tx = mock(TransactionTemplate.class);

        session = new InterviewSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setSourceType(InterviewSourceType.PLATFORM_RESUME);
        session.setResumeVersionId(RESUME_VERSION_ID);
        session.setJobDescriptionId(JOB_ID);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setOutputLanguage(InterviewOutputLanguage.ZH_CN);
        session.setStatus(InterviewStatus.GENERATING_QUESTION);
        session.setExecutionMode(ExecutionMode.AI);
        session.setTargetQuestionCount(6);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);

        // TX1 内创建会话并返回 StartPreparation（record 为私有嵌套，用反射构造）
        doAnswer(invocation -> {
            attemptRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY);
            sessionRepository.saveAndFlush(any());
            if (!operationSupport.hasInterviewConsent(USER_ID, session)) {
                throw new AssertionError("consent should be granted");
            }
            operationSupport.checkInterviewQuota(USER_ID);
            InterviewAiAttempt attempt = new InterviewAiAttempt();
            attempt.setId(ATTEMPT_ID);
            attempt.setSessionId(SESSION_ID);
            attempt.setOperationType(AiAttemptOperationType.INITIAL_QUESTION);
            attemptRepository.save(attempt);
            return newPreparation(SESSION_ID, false, ATTEMPT_ID);
        }).when(tx).execute(any());

        // TX2 直接落库：执行 Consumer
        doAnswer(invocation -> {
            java.util.function.Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        InterviewStateAssembler assembler = new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        promptContextAssembler = mock(InterviewPromptContextAssembler.class);
        operationSupport = mock(InterviewOperationSupport.class);
        when(operationSupport.buildStartFingerprint(any(), any())).thenReturn("fp");
        when(operationSupport.hasInterviewConsent(any(), any())).thenReturn(true);
        service = new InterviewStartService(sessionRepository, attemptRepository, tx, assembler,
                promptContextAssembler, operationSupport);
    }

    private Object newPreparation(Long sessionId, boolean replayed, Long attemptId) throws Exception {
        Class<?> clazz = Class.forName("com.intelligentresume.interview.service.InterviewStartService$StartPreparation");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(sessionId, replayed, attemptId);
    }

    private InterviewAiAttempt attempt() {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setSessionId(SESSION_ID);
        attempt.setOperationType(AiAttemptOperationType.INITIAL_QUESTION);
        return attempt;
    }

    @Test
    @DisplayName("start：提供 initialQuestion 时直达 AWAITING_ANSWER，不调用首题 AI")
    void start_withInitialQuestion_skipsAiFirstQuestion() throws Exception {
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.PLATFORM_RESUME, RESUME_VERSION_ID, null, JOB_ID,
                InterviewMode.TECHNICAL, 6, InterviewOutputLanguage.ZH_CN, "编辑后的练习题");

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
        when(recordRepository.countBySessionId(SESSION_ID)).thenReturn(0L);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt()));

        InterviewStateResponse response = service.start(request, USER_ID, IDEM_KEY);

        assertEquals(InterviewStatus.AWAITING_ANSWER, response.getStatus());
        assertEquals("编辑后的练习题", response.getCurrentQuestion());
        assertEquals(InterviewSourceType.PLATFORM_RESUME, response.getSourceType());
        assertEquals(RESUME_VERSION_ID, response.getResumeVersionId());
        assertEquals(JOB_ID, response.getJobDescriptionId());
        assertEquals(ExecutionMode.AI, response.getExecutionMode());
        // 首题 AI 未被调用
        verify(operationSupport, never()).callAiForFirstQuestion(any(), any());
        // 练习会话沿用真实来源（resumeVersionId 持久化）
        verify(sessionRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("start：initialQuestion 为空白时回退为 AI 首题（不会把空白当练习题）")
    void start_blankInitialQuestion_fallsBackToAi() throws Exception {
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.PLATFORM_RESUME, RESUME_VERSION_ID, null, JOB_ID,
                InterviewMode.TECHNICAL, 6, InterviewOutputLanguage.ZH_CN, "   ");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
        when(recordRepository.countBySessionId(SESSION_ID)).thenReturn(0L);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt()));

        InterviewCoachResponse.InitialQuestion initial = new InterviewCoachResponse.InitialQuestion();
        initial.setQuestion("这是一个由 AI 生成的首题问题");
        initial.setFocus("通用");
        initial.setExpectedSignals(List.of("结构清晰"));
        initial.setCoverageTags(List.of("intro"));
        when(operationSupport.callAiForFirstQuestion(any(), eq(USER_ID)))
                .thenReturn(new InterviewAiService.AiInvocation<>(initial, "req-1"));

        InterviewStateResponse response = service.start(request, USER_ID, IDEM_KEY);

        // 空白 initialQuestion → 走 AI 生成首题路径，最终仍 AWAITING_ANSWER
        assertEquals(InterviewStatus.AWAITING_ANSWER, response.getStatus());
        assertEquals("这是一个由 AI 生成的首题问题", response.getCurrentQuestion());
        verify(operationSupport).callAiForFirstQuestion(any(), eq(USER_ID));
    }
}
