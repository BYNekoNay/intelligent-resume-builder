package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
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
 * 历史面试会话列表单测：仅 COMPLETED、JD 归属校验、题数与平均分聚合。
 */
class InterviewHistoryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long JOB_ID = 30L;

    private InterviewSessionRepository sessionRepository;
    private InterviewRecordRepository recordRepository;
    private JobDescriptionRepository jobRepository;
    private InterviewHistoryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(InterviewSessionRepository.class);
        recordRepository = mock(InterviewRecordRepository.class);
        jobRepository = mock(JobDescriptionRepository.class);
        service = new InterviewHistoryService(sessionRepository, recordRepository, jobRepository);
    }

    private InterviewSession completedSession(Long id) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setUserId(USER_ID);
        session.setJobDescriptionId(JOB_ID);
        session.setResumeVersionId(10L);
        session.setSourceType(InterviewSourceType.PLATFORM_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setExecutionMode(ExecutionMode.AI);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        session.setTargetQuestionCount(6);
        session.setStatus(InterviewStatus.COMPLETED);
        return session;
    }

    private InterviewRecord record(int roundNo, int score, long sessionId) {
        InterviewRecord record = new InterviewRecord();
        record.setId((long) roundNo);
        record.setRoundNo(roundNo);
        record.setRoundScore(score);
        record.setSessionId(sessionId);
        return record;
    }

    @Test
    @DisplayName("list：仅返回 COMPLETED 会话，服务端聚合题数与平均分（批量加载避免 N+1）")
    void list_returnsCompletedWithAggregation() {
        InterviewSession completed = completedSession(1L);
        when(sessionRepository.findCompletedByUserId(USER_ID, InterviewStatus.COMPLETED, null))
                .thenReturn(List.of(completed));
        when(recordRepository.findBySessionIdInOrderByCreatedAtAsc(List.of(1L)))
                .thenReturn(List.of(record(1, 60, 1L), record(2, 80, 1L), record(3, 100, 1L)));

        var result = service.list(USER_ID, null);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(3, result.get(0).actualQuestionCount());
        assertEquals(80, result.get(0).totalScore());
        assertEquals(JOB_ID, result.get(0).jobDescriptionId());
        assertEquals(InterviewSourceType.PLATFORM_RESUME, result.get(0).sourceType());
        // 仓库查询参数必须锁定 COMPLETED，保证不列出进行中的会话
        verify(sessionRepository).findCompletedByUserId(eq(USER_ID), eq(InterviewStatus.COMPLETED), eq(null));
        // 使用一次批量查询，而非逐会话 N 次查询
        verify(recordRepository).findBySessionIdInOrderByCreatedAtAsc(List.of(1L));
    }

    @Test
    @DisplayName("list：指定 JD 不属于当前用户抛 40401")
    void list_foreignJobDescription_throwsNotFound() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.list(USER_ID, JOB_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(sessionRepository, never()).findCompletedByUserId(any(), any(), any());
    }

    @Test
    @DisplayName("list：按 JD 筛选时透传 jobDescriptionId")
    void list_passesJobFilter() {
        when(jobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(new JobDescription()));
        when(sessionRepository.findCompletedByUserId(USER_ID, InterviewStatus.COMPLETED, JOB_ID))
                .thenReturn(List.of(completedSession(2L)));
        when(recordRepository.findBySessionIdInOrderByCreatedAtAsc(List.of(2L))).thenReturn(List.of(record(1, 50, 2L)));

        var result = service.list(USER_ID, JOB_ID);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).id());
        assertEquals(1, result.get(0).actualQuestionCount());
        assertEquals(50, result.get(0).totalScore());
        verify(sessionRepository).findCompletedByUserId(USER_ID, InterviewStatus.COMPLETED, JOB_ID);
    }
}
