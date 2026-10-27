package com.intelligentresume.interview.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试薄弱项练习服务单元测试：会话未完成 / 同意校验 / 任务创建。
 */
class InterviewFollowUpAiServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 1L;

    private InterviewSessionRepository sessionRepository;
    private AiConsentService consentService;
    private AiTaskService aiTaskService;
    private InterviewFollowUpAiService service;
    private InterviewFollowUpPromptAssembler promptAssembler;
    private AiPreviewService previewService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        consentService = mock(AiConsentService.class);
        aiTaskService = mock(AiTaskService.class);
        InterviewPromptContextAssembler contextAssembler = mock(InterviewPromptContextAssembler.class);
        promptAssembler = new InterviewFollowUpPromptAssembler(contextAssembler);
        previewService = new AiPreviewService(new ObjectMapper());
        AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new InterviewFollowUpAiService(sessionRepository, promptAssembler, consentService, aiTaskService,
                providerRegistry, objectMapper, validator, previewService, new AiPreviewGuard(previewService));
    }

    /** 用**服务端同一组装器**算令牌：与 AiPreviewGuard 的校验输入同源。 */
    private String tokenFor(InterviewSession session, String weakness) {
        return previewService.tokenFor(com.intelligentresume.ai.task.domain.AiTaskType.INTERVIEW_COACH,
                promptAssembler.buildProviderInput(session, weakness, USER_ID));
    }

    private InterviewSession session(InterviewStatus status) {
        InterviewSession session = new InterviewSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setSourceType(InterviewSourceType.PLATFORM_RESUME);
        session.setResumeVersionId(10L);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(status);
        return session;
    }

    @Test
    @DisplayName("createFollowUpTask: 会话未完成抛 40901 且不创建任务")
    void createFollowUpTask_notCompleted_throwsConflict() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(session(InterviewStatus.AWAITING_ANSWER)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFollowUpTask(SESSION_ID, "缺少量化成果", USER_ID, "key-1", null));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(aiTaskService, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("createFollowUpTask: 未授权抛 40302 且不创建任务")
    void createFollowUpTask_withoutConsent_throwsConsentRequired() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(session(InterviewStatus.COMPLETED)));
        when(consentService.hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"),
                eq(List.of("RESUME", "INTERVIEW_ANSWER")))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFollowUpTask(SESSION_ID, "缺少量化成果", USER_ID, "key-1", null));
        assertEquals(ErrorCode.CONSENT_REQUIRED, ex.getErrorCode());
        verify(aiTaskService, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("createFollowUpTask: 合法请求创建 INTERVIEW_COACH 任务并返回 202 响应")
    void createFollowUpTask_valid_createsTask() {
        InterviewSession completed = session(InterviewStatus.COMPLETED);
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(completed));
        when(consentService.hasValidConsent(eq(USER_ID), eq("INTERVIEW_COACH"),
                eq(List.of("RESUME", "INTERVIEW_ANSWER")))).thenReturn(true);
        AiTaskStatusResponse expected = new AiTaskStatusResponse(9L, com.intelligentresume.ai.task.domain.AiTaskType.INTERVIEW_COACH,
                null, null, com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING, null, null, null, null, 0, null, null);
        when(aiTaskService.create(any(), eq("key-1"), eq(USER_ID), any())).thenReturn(expected);

        var result = service.createFollowUpTask(SESSION_ID, "缺少量化成果", USER_ID, "key-1",
                tokenFor(completed, "缺少量化成果"));

        assertEquals(9L, result.id());
        verify(aiTaskService).create(any(), eq("key-1"), eq(USER_ID), any());
    }

    // R32 的令牌强制**只能由 IT 覆盖**：守卫是 AiTaskService.create 的 beforeCreate 回调，
    // 而本类把 AiTaskService mock 掉了，守卫根本不会执行 —— 在这里断言"缺令牌会抛错"永远得不到真值。
    // 覆盖见 InterviewFollowUpPreviewProtocolIT（缺令牌 40001 / 令牌失效 40901 / 预览只读 / 同源）。

    @Test
    @DisplayName("createFollowUpTask: 空白薄弱项抛 40001")
    void createFollowUpTask_blankWeakness_throwsValidation() {
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(session(InterviewStatus.COMPLETED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFollowUpTask(SESSION_ID, "   ", USER_ID, "key-1", null));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }
}
