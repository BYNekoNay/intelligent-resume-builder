package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.dto.InterviewReportResponse;
import com.intelligentresume.interview.dto.InterviewStateResponse;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试结束与报告服务单测：终态约束、维度聚合、来源类型。
 */
class InterviewReportServiceTest {

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewReportService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        InterviewAiAttemptRepository attemptRepository = mock(InterviewAiAttemptRepository.class);
        InterviewStateAssembler assembler = new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
        service = new InterviewReportService(sessionRepository, recordRepository, assembler);
    }

    private InterviewSession session(Long id, InterviewStatus status, Integer target) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setStatus(status);
        session.setExecutionMode(ExecutionMode.AI);
        session.setTargetQuestionCount(target);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);
        return session;
    }

    private InterviewRecord record(int roundNo, int score, EvaluationSource source, String strength) {
        InterviewRecord record = new InterviewRecord();
        record.setId((long) roundNo);
        record.setRoundNo(roundNo);
        record.setQuestionText("Q" + roundNo);
        record.setAnswerText("A" + roundNo);
        record.setRoundScore(score);
        record.setEvaluationSource(source);
        record.setFeedbackJson(Map.of(
                "dimensionScores", Map.of("relevance", 10, "evidenceSpecificity", 8,
                        "structureClarity", 6, "roleCompetency", 5, "authenticityReflection", 2),
                "strengths", List.of(strength),
                "improvements", List.of("improve-" + roundNo)
        ));
        return record;
    }

    // ---- finish ----

    @Test
    @DisplayName("finish：AI 操作进行中拒绝")
    void finish_rejectsWhileAiInProgress() {
        when(sessionRepository.findByIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(session(1L, InterviewStatus.GENERATING_QUESTION, 6)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.finish(1L, 7L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("Cannot finish while an AI operation is in progress", ex.getMessage());
    }

    @Test
    @DisplayName("finish：未完成任何题目拒绝")
    void finish_rejectsWithoutRecords() {
        when(sessionRepository.findByIdAndUserIdForUpdate(1L, 7L))
                .thenReturn(Optional.of(session(1L, InterviewStatus.AWAITING_ANSWER, 6)));
        when(recordRepository.countBySessionId(1L)).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.finish(1L, 7L));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertEquals("至少完成 1 题后才能结束面试", ex.getMessage());
    }

    @Test
    @DisplayName("finish：合法结束置为 COMPLETED/USER_FINISHED")
    void finish_marksCompleted() {
        InterviewSession session = session(1L, InterviewStatus.AWAITING_ANSWER, 6);
        session.setCurrentQuestion("Q1");
        when(sessionRepository.findByIdAndUserIdForUpdate(1L, 7L)).thenReturn(Optional.of(session));
        when(recordRepository.countBySessionId(1L)).thenReturn(2L);
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(record(1, 60, EvaluationSource.AI, "s1"), record(2, 80, EvaluationSource.AI, "s2")));

        InterviewStateResponse response = service.finish(1L, 7L);

        assertEquals(InterviewStatus.COMPLETED, session.getStatus());
        assertEquals(CompletionReason.USER_FINISHED, session.getCompletionReason());
        verify(sessionRepository).save(session);
        assertEquals(InterviewStatus.COMPLETED, response.getStatus());
    }

    // ---- report ----

    @Test
    @DisplayName("report：未完成面试拒绝")
    void report_rejectsWhenNotCompleted() {
        when(sessionRepository.findByIdAndUserId(1L, 7L))
                .thenReturn(Optional.of(session(1L, InterviewStatus.AWAITING_ANSWER, 6)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.report(1L, 7L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("面试未完成", ex.getMessage());
    }

    @Test
    @DisplayName("report：无记录返回零值报告")
    void report_emptyRecords() {
        when(sessionRepository.findByIdAndUserId(1L, 7L))
                .thenReturn(Optional.of(session(1L, InterviewStatus.COMPLETED, 6)));
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        InterviewReportResponse response = service.report(1L, 7L);

        assertEquals(0, response.totalScore());
        assertEquals("尚未完成任何回答", response.summary());
    }

    @Test
    @DisplayName("report：聚合五维平均、去重反馈、混合来源")
    void report_aggregatesDimensions() {
        InterviewSession session = session(1L, InterviewStatus.COMPLETED, 6);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        when(sessionRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(session));
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(
                record(1, 60, EvaluationSource.AI, "same"),
                record(2, 80, EvaluationSource.RULE, "same"),
                record(3, 40, EvaluationSource.RULE, "other")
        ));

        InterviewReportResponse response = service.report(1L, 7L);

        assertEquals(60, response.totalScore());
        assertEquals(3, response.actualQuestionCount());
        assertEquals(CompletionReason.USER_FINISHED, response.completionReason());
        assertEquals(EvaluationSource.MIXED, response.evaluationSource());
        assertEquals(1, response.aiEvaluatedRounds());
        assertEquals(2, response.ruleEvaluatedRounds());
        assertEquals(List.of("same", "other"), response.strengths());
        assertEquals(10, response.dimensionScores().relevance());
        assertEquals(8, response.dimensionScores().evidenceSpecificity());
        assertEquals(6, response.dimensionScores().structureClarity());
        assertEquals(5, response.dimensionScores().roleCompetency());
        assertEquals(2, response.dimensionScores().authenticityReflection());
    }

    @Test
    @DisplayName("report：按 interview_record 组装逐轮明细 rounds（含单轮五维/反馈/建议答案）")
    void report_assemblesRoundDetails() {
        InterviewSession session = session(1L, InterviewStatus.COMPLETED, 6);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        when(sessionRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(session));
        InterviewRecord r1 = record(1, 60, EvaluationSource.AI, "s1");
        r1.setFeedbackJson(Map.of(
                "dimensionScores", Map.of("relevance", 10, "evidenceSpecificity", 8,
                        "structureClarity", 6, "roleCompetency", 5, "authenticityReflection", 2),
                "strengths", List.of("s1"),
                "improvements", List.of("i1"),
                "suggestedAnswer", "建议答案1"));
        InterviewRecord r2 = record(2, 80, EvaluationSource.RULE, "s2");
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(r1, r2));

        InterviewReportResponse response = service.report(1L, 7L);

        assertNotNull(response.rounds());
        assertEquals(2, response.rounds().size());
        InterviewReportResponse.RoundDetail first = response.rounds().get(0);
        assertEquals(1, first.roundNo());
        assertEquals("Q1", first.questionText());
        assertEquals("A1", first.answerText());
        assertEquals(60, first.roundScore());
        assertEquals(EvaluationSource.AI, first.evaluationSource());
        assertNotNull(first.dimensionScores());
        assertEquals(10, first.dimensionScores().relevance());
        assertEquals(List.of("s1"), first.strengths());
        assertEquals(List.of("i1"), first.improvements());
        assertEquals("建议答案1", first.suggestedAnswer());
        // 第二条 RULE 记录也有逐轮明细
        assertEquals(EvaluationSource.RULE, response.rounds().get(1).evaluationSource());
        assertEquals(80, response.rounds().get(1).roundScore());
    }
}
