package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态组装器单测：计数、LastEvaluation、AiFailureInfo、反馈去重。
 */
class InterviewStateAssemblerTest {

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private InterviewAiAttemptRepository attemptRepository;
    private InterviewStateAssembler assembler;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        attemptRepository = mock(InterviewAiAttemptRepository.class);
        assembler = new InterviewStateAssembler(sessionRepository, recordRepository, attemptRepository);
    }

    private InterviewSession session(Long id, InterviewStatus status, int completed, Integer target) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setStatus(status);
        session.setExecutionMode(ExecutionMode.AI);
        session.setCurrentQuestion("Q1");
        session.setTargetQuestionCount(target);
        session.setMinQuestionCount(3);
        session.setMaxQuestionCount(9);
        return session;
    }

    private InterviewRecord record(Long id, int roundNo, int score, String strengths) {
        InterviewRecord record = new InterviewRecord();
        record.setId(id);
        record.setRoundNo(roundNo);
        record.setQuestionText("Q" + roundNo);
        record.setAnswerText("A" + roundNo);
        record.setRoundScore(score);
        record.setEvaluationSource(EvaluationSource.AI);
        record.setFeedbackJson(Map.of(
                "dimensionScores", Map.of("relevance", 10, "evidenceSpecificity", 8,
                        "structureClarity", 6, "roleCompetency", 5, "authenticityReflection", 2),
                "strengths", List.of(strengths),
                "improvements", List.of("improve-" + roundNo),
                "suggestedAnswer", "suggested-" + roundNo
        ));
        return record;
    }

    @Test
    @DisplayName("buildStateResponse：统计完成数并自动加载最近评估")
    void buildStateResponse_countsAndLoadsLastEvaluation() {
        when(recordRepository.countBySessionId(1L)).thenReturn(2L);
        when(recordRepository.findBySessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(record(11L, 1, 60, "s1"), record(12L, 2, 80, "s2")));
        InterviewSession session = session(1L, InterviewStatus.AWAITING_ANSWER, 2, 6);

        InterviewStateResponse response = assembler.buildStateResponse(session, null, null);

        assertEquals(1L, response.getInterviewId());
        assertEquals(InterviewStatus.AWAITING_ANSWER, response.getStatus());
        assertEquals(2, response.getCompletedQuestionCount());
        assertEquals(3, response.getCurrentQuestionNo());
        assertEquals(6, response.getTargetQuestionCount());
        assertEquals(12L, response.getLastEvaluation().getRecordId());
        assertEquals(2, response.getLastEvaluation().getRoundNo());
        assertEquals(80, response.getLastEvaluation().getRoundScore());
        assertEquals(10, response.getLastEvaluation().getDimensionScores().getRelevance());
        assertEquals(List.of("s2"), response.getLastEvaluation().getStrengths());
        assertEquals("suggested-2", response.getLastEvaluation().getSuggestedAnswer());
        assertNull(response.getAiFailure());
    }

    @Test
    @DisplayName("buildStateResponse：无记录时 currentQuestionNo 为 null")
    void buildStateResponse_noRecords() {
        when(recordRepository.countBySessionId(1L)).thenReturn(0L);
        InterviewSession session = session(1L, InterviewStatus.GENERATING_QUESTION, 0, 6);

        InterviewStateResponse response = assembler.buildStateResponse(session, null, null);

        assertNull(response.getCurrentQuestionNo());
        assertNull(response.getLastEvaluation());
    }

    @Test
    @DisplayName("buildAiFailure(attempt)：映射 stage/retryable/再授权/messageCode")
    void buildAiFailure_fromAttempt() {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setId(7L);
        attempt.setOperationType(AiAttemptOperationType.INITIAL_QUESTION);
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setRetryable(true);
        attempt.setErrorCode("RATE_LIMITED");

        InterviewStateResponse.AiFailureInfo info = assembler.buildAiFailure(attempt);

        assertEquals(7L, info.getOperationId());
        assertEquals("INITIAL_QUESTION", info.getStage());
        assertTrue(info.isRetryable());
        assertFalse(info.isReauthorizationRequired());
        assertEquals("RATE_LIMITED", info.getMessageCode());
    }

    @Test
    @DisplayName("buildAiFailure(attempt)：FORBIDDEN 触发再授权")
    void buildAiFailure_forbiddenRequiresReauth() {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setId(8L);
        attempt.setOperationType(AiAttemptOperationType.ANSWER_EVALUATION);
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setRetryable(false);
        attempt.setErrorCode("FORBIDDEN");

        InterviewStateResponse.AiFailureInfo info = assembler.buildAiFailure(attempt);

        assertFalse(info.isRetryable());
        assertTrue(info.isReauthorizationRequired());
        assertEquals("FORBIDDEN", info.getMessageCode());
    }

    @Test
    @DisplayName("buildAiFailure(operationId, stage, exception)：按错误码推导 retryable")
    void buildAiFailure_fromException() {
        InterviewStateResponse.AiFailureInfo forbidden =
                assembler.buildAiFailure(1L, "INITIAL_QUESTION", new BusinessException(ErrorCode.FORBIDDEN, "x"));
        assertFalse(forbidden.isRetryable());
        assertTrue(forbidden.isReauthorizationRequired());

        InterviewStateResponse.AiFailureInfo aiFailure =
                assembler.buildAiFailure(2L, "ANSWER_EVALUATION", new BusinessException(ErrorCode.AI_FAILURE, "y"));
        assertTrue(aiFailure.isRetryable());
        assertFalse(aiFailure.isReauthorizationRequired());
        assertEquals("AI_FAILURE", aiFailure.getMessageCode());
    }

    @Test
    @DisplayName("distinctFeedback：去重并按插入序保留，最多 5 条")
    void distinctFeedback_deduplicatesAndLimits() {
        List<InterviewRecord> records = List.of(
                record(1L, 1, 60, "same"),
                record(2L, 2, 70, "same"),
                record(3L, 3, 80, "other")
        );

        List<String> strengths = assembler.distinctFeedback(records, "strengths");

        assertEquals(List.of("same", "other"), strengths);
    }

    @Test
    @DisplayName("num：Number 取整，非数字返回 0")
    void num_coercesNumbers() {
        assertEquals(5, assembler.num(5));
        assertEquals(5, assembler.num(5.9));
        assertEquals(0, assembler.num("abc"));
        assertEquals(0, assembler.num(null));
    }

    @Test
    @DisplayName("owned：找不到会话抛 40401")
    void owned_missingSession_throwsNotFound() {
        when(sessionRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> assembler.owned(1L, 7L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("notFound/validation：返回对应错误码")
    void errorFactories() {
        assertEquals(ErrorCode.NOT_FOUND, assembler.notFound("x").getErrorCode());
        assertEquals(ErrorCode.VALIDATION, assembler.validation("x").getErrorCode());
    }
}
