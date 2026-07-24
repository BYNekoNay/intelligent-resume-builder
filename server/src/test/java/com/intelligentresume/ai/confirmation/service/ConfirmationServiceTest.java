package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.dto.ConfirmResponse;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem.Decision;
import com.intelligentresume.ai.confirmation.dto.RejectRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConfirmationService 单元测试（Mockito）。
 * 覆盖：confirm 成功、reject、缺少幂等键、并发幂等。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock private AiTaskRepository taskRepository;
    @Mock private DraftCommitService draftCommitService;
    @Mock private ResumeVersionRepository versionRepository;

    private ConfirmationService service;

    private static final Long TASK_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 7, 20, 10, 30, 0);

    @BeforeEach
    void setUp() {
        service = new ConfirmationService(taskRepository, draftCommitService, versionRepository);
    }

    private AiTask buildTask(ConfirmationStatus confStatus) {
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setConfirmationStatus(confStatus);
        task.setUpdatedAt(UPDATED_AT);
        return task;
    }

    @Test
    @DisplayName("正常路径: confirm 成功并返回版本号")
    void confirm_success_returnsVersion() {
        AiTask task = buildTask(ConfirmationStatus.PENDING);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        DraftCommitService.CommitResult commitResult =
                new DraftCommitService.CommitResult(50L, 3, List.of(), List.of(), 10L);
        when(draftCommitService.commit(eq(TASK_ID), any(), any(), eq(UPDATED_AT), eq(USER_ID), any(), any()))
                .thenReturn(commitResult);

        ConfirmRequest req = new ConfirmRequest(UPDATED_AT,
                List.of(new ConfirmedDraftItem("basics", Decision.ACCEPT, null)), null, null, null);

        ConfirmResponse resp = service.confirm(TASK_ID, req, "idem-key-1", USER_ID);

        assertEquals(50L, resp.resumeVersionId());
        assertEquals(3, resp.versionNo());
        assertEquals(10L, resp.resumeId());
        verify(draftCommitService).commit(eq(TASK_ID), any(), any(), eq(UPDATED_AT), eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("正常路径: reject 不创建版本,任务变为 REJECTED")
    void reject_marksRejected() {
        AiTask task = buildTask(ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        RejectRequest req = new RejectRequest(UPDATED_AT);
        service.reject(TASK_ID, req, USER_ID);

        assertEquals(ConfirmationStatus.REJECTED, task.getConfirmationStatus());
        verify(taskRepository).save(task);
        verify(draftCommitService, never()).commit(anyLong(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("正常路径: 缺少 Idempotency-Key 抛 VALIDATION")
    void confirm_missingIdempotencyKey_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID,
                        new ConfirmRequest(UPDATED_AT, List.of(), null, null, null),
                        null, USER_ID));
        assertEquals(ErrorCode.VALIDATION.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: 双标签页并发确认,已 CONFIRMED 返回原版本(幂等)")
    void confirm_concurrent_onlyOneSucceeds() {
        // 模拟：任务已被第一个标签页 CONFIRMED
        AiTask task = buildTask(ConfirmationStatus.CONFIRMED);
        task.setResultResumeVersionId(50L);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        ResumeVersion version = new ResumeVersion();
        version.setId(50L);
        version.setVersionNo(3);
        version.setResumeId(10L);
        when(versionRepository.findById(50L)).thenReturn(Optional.of(version));

        ConfirmRequest req = new ConfirmRequest(UPDATED_AT,
                List.of(new ConfirmedDraftItem("basics", Decision.ACCEPT, null)), null, null, null);

        // 第二个标签页 confirm → 返回原版本（幂等）
        ConfirmResponse resp = service.confirm(TASK_ID, req, "idem-key-2", USER_ID);

        assertEquals(50L, resp.resumeVersionId());
        assertEquals(3, resp.versionNo());
        // 不应调用 DraftCommitService
        verify(draftCommitService, never()).commit(anyLong(), any(), any(), any(), anyLong(), any(), any());
    }
}
