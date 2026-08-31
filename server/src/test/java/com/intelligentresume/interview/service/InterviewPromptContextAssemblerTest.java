package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.StartInterviewRequest;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 上下文组装单测：JD 有无、外部简历脱敏、历史截断、来源校验。
 */
class InterviewPromptContextAssemblerTest {

    private static final Long USER_ID = 7L;

    private InterviewContextSanitizer sanitizer;
    private JobDescriptionRepository jobDescriptionRepository;
    private ResumeRepository resumeRepository;
    private ResumeVersionRepository resumeVersionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewPromptContextAssembler assembler;

    @BeforeEach
    void setUp() {
        sanitizer = mock(InterviewContextSanitizer.class);
        jobDescriptionRepository = mock(JobDescriptionRepository.class);
        resumeRepository = mock(ResumeRepository.class);
        resumeVersionRepository = mock(ResumeVersionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        assembler = new InterviewPromptContextAssembler(
                sanitizer, jobDescriptionRepository, resumeRepository, resumeVersionRepository, recordRepository);
        when(sanitizer.truncateJdText("JD_TEXT")).thenReturn("JD_TRUNCATED");
        when(sanitizer.sanitizeExternalResume("EXT_TEXT")).thenReturn("EXT_SANITIZED");
        when(sanitizer.buildHistoryContext(List.of())).thenReturn("HISTORY");
        when(sanitizer.truncateCurrentAnswer("ANSWER")).thenReturn("ANSWER_TRUNCATED");
        when(sanitizer.untrustedDataMarker()).thenReturn("[UNTRUSTED]");
    }

    private InterviewSession session(Long jobId, InterviewSourceType sourceType, Long resumeVersionId, String externalText) {
        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setJobDescriptionId(jobId);
        session.setSourceType(sourceType);
        session.setResumeVersionId(resumeVersionId);
        session.setExternalResumeText(externalText);
        session.setInterviewMode(InterviewMode.COMPREHENSIVE);
        session.setStatus(InterviewStatus.AWAITING_ANSWER);
        session.setExecutionMode(ExecutionMode.AI);
        session.setCurrentQuestion("当前问题");
        session.setTargetQuestionCount(6);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);
        return session;
    }

    private ResumeVersion version(Long resumeId, LocalDateTime deletedAt, Map<String, Object> resumeJson) {
        ResumeVersion version = new ResumeVersion();
        version.setResumeId(resumeId);
        version.setDeletedAt(deletedAt);
        version.setResumeJson(resumeJson);
        return version;
    }

    @Test
    @DisplayName("首题上下文：有 JD 时加载并截断")
    void buildFirstQuestionContext_withJd() {
        JobDescription job = mockJob("JD_TEXT");
        when(jobDescriptionRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.of(job));
        InterviewSession session = session(10L, InterviewSourceType.EXTERNAL_RESUME, null, "EXT_TEXT");

        String ctx = assembler.buildFirstQuestionContext(session, USER_ID);

        assertTrue(ctx.contains("Job Description:\nJD_TRUNCATED"));
        assertTrue(ctx.contains("Resume:\nEXT_SANITIZED"));
        assertTrue(ctx.contains("Interview Mode: COMPREHENSIVE"));
    }

    @Test
    @DisplayName("首题上下文：无 JD 时输出 general interview 占位")
    void buildFirstQuestionContext_withoutJd() {
        InterviewSession session = session(null, InterviewSourceType.EXTERNAL_RESUME, null, "EXT_TEXT");

        String ctx = assembler.buildFirstQuestionContext(session, USER_ID);

        assertTrue(ctx.contains("Job Description: None (general interview)"));
    }

    @Test
    @DisplayName("评估上下文：包含进度、历史、当前问答与不可信标记")
    void buildEvaluationContext_includesAllSections() {
        when(recordRepository.countBySessionId(1L)).thenReturn(1L);
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        InterviewSession session = session(10L, InterviewSourceType.EXTERNAL_RESUME, null, "EXT_TEXT");

        String ctx = assembler.buildEvaluationContext(session, "ANSWER", USER_ID);

        assertTrue(ctx.contains("Interview Progress:\ncompletedQuestionCount: 1"));
        assertTrue(ctx.contains("minQuestionCount: 3"));
        assertTrue(ctx.contains("targetQuestionCount: 6"));
        assertTrue(ctx.contains("maxQuestionCount: 9"));
        assertTrue(ctx.contains("Conversation History:\nHISTORY"));
        assertTrue(ctx.contains("Current Question:\n当前问题"));
        assertTrue(ctx.contains("Current Answer:\nANSWER_TRUNCATED"));
        assertTrue(ctx.contains("[UNTRUSTED]"));
    }

    @Test
    @DisplayName("平台简历上下文：使用脱敏摘要")
    void appendResumeContext_platformResume() {
        when(resumeVersionRepository.findById(20L))
                .thenReturn(Optional.of(version(1L, null, Map.of("name", "Alice"))));
        when(resumeRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(new Resume()));
        when(sanitizer.sanitizePlatformResume(Map.of("name", "Alice")))
                .thenReturn(Map.of("resumeSummary", "SUMMARY"));
        InterviewSession session = session(null, InterviewSourceType.PLATFORM_RESUME, 20L, null);

        StringBuilder ctx = new StringBuilder();
        assembler.appendResumeContext(ctx, session, USER_ID);

        assertTrue(ctx.toString().contains("Resume:\nSUMMARY"));
    }

    @Test
    @DisplayName("外部简历上下文：脱敏后追加")
    void appendResumeContext_externalResume() {
        InterviewSession session = session(null, InterviewSourceType.EXTERNAL_RESUME, null, "EXT_TEXT");

        StringBuilder ctx = new StringBuilder();
        assembler.appendResumeContext(ctx, session, USER_ID);

        assertTrue(ctx.toString().contains("Resume:\nEXT_SANITIZED"));
    }

    @Test
    @DisplayName("validateSource：平台简历缺少版本抛校验错误")
    void validateSource_platformResumeWithoutVersion() {
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.PLATFORM_RESUME, null, null, null, InterviewMode.COMPREHENSIVE, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.validateSource(request, USER_ID));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("平台简历来源必须选择简历版本", ex.getMessage());
    }

    @Test
    @DisplayName("validateSource：外部简历文本为空抛校验错误")
    void validateSource_externalResumeBlank() {
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.EXTERNAL_RESUME, null, "   ", null, InterviewMode.COMPREHENSIVE, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.validateSource(request, USER_ID));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("外部简历来源必须提供简历文本", ex.getMessage());
    }

    @Test
    @DisplayName("validateSource：JD 不属于当前用户抛 40401")
    void validateSource_jdNotOwned() {
        when(jobDescriptionRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.empty());
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.EXTERNAL_RESUME, null, "ext", 10L, InterviewMode.COMPREHENSIVE, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> assembler.validateSource(request, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("岗位不存在", ex.getMessage());
    }

    @Test
    @DisplayName("validateSource：合法来源通过")
    void validateSource_valid() {
        JobDescription job = mockJob("JD");
        when(jobDescriptionRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.of(job));
        StartInterviewRequest request = new StartInterviewRequest(
                InterviewSourceType.EXTERNAL_RESUME, null, "ext", 10L, InterviewMode.COMPREHENSIVE, null, null);

        assembler.validateSource(request, USER_ID); // 不应抛异常
    }

    @Test
    @DisplayName("findOwnedResumeVersion：已删除版本抛 40401")
    void findOwnedResumeVersion_deleted() {
        when(resumeVersionRepository.findById(20L))
                .thenReturn(Optional.of(version(1L, LocalDateTime.now(), Map.of())));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> assembler.findOwnedResumeVersion(20L, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("简历版本不存在", ex.getMessage());
    }

    @Test
    @DisplayName("findOwnedResumeVersion：简历不属于当前用户抛 40401")
    void findOwnedResumeVersion_resumeNotOwned() {
        when(resumeVersionRepository.findById(20L)).thenReturn(Optional.of(version(1L, null, Map.of())));
        when(resumeRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> assembler.findOwnedResumeVersion(20L, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("findOwnedResumeVersion：合法返回版本")
    void findOwnedResumeVersion_ok() {
        when(resumeVersionRepository.findById(20L)).thenReturn(Optional.of(version(1L, null, Map.of())));
        when(resumeRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(new Resume()));

        ResumeVersion found = assembler.findOwnedResumeVersion(20L, USER_ID);

        assertEquals(1L, found.getResumeId());
    }

    private JobDescription mockJob(String jdText) {
        JobDescription job = mock(JobDescription.class);
        when(job.getJdText()).thenReturn(jdText);
        return job;
    }
}
