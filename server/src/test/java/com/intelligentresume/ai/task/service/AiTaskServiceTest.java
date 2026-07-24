package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiTaskService 单元测试（Mockito）。
 * 覆盖:同意校验、配额校验、幂等性（同指纹返回/异指纹冲突）、任务创建。
 */
@ExtendWith(MockitoExtension.class)
class AiTaskServiceTest {

    @Mock private AiTaskRepository taskRepository;
    @Mock private AiConsentService consentService;
    @Mock private AiQuotaService quotaService;

    private IdempotencyService idempotencyService;
    private AiTaskService service;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService();
        service = new AiTaskService(taskRepository, consentService, quotaService, idempotencyService);
    }

    @Test
    @DisplayName("已授权用户创建任务 → PENDING")
    void create_consented_returnsPending() {
        when(consentService.hasValidConsent(100L)).thenReturn(true);
        doNothing().when(quotaService).check(eq(100L), any());
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                eq(100L), any(), anyString())).thenReturn(Optional.empty());
        when(taskRepository.save(any(AiTask.class))).thenAnswer(inv -> {
            AiTask t = inv.getArgument(0);
            t.setId(1L);
            t.setCreatedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            return t;
        });

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, Map.of("key", "value"), null, null, null, null, null, null);

        AiTaskStatusResponse response = service.create(req, "idem-key-1", 100L);

        assertEquals(AiTaskStatus.PENDING, response.status());
        assertEquals(AiTaskType.JOB_GENERATION, response.taskType());
        assertNull(response.jobDescriptionId());
        assertEquals(0, response.retryCount());
    }

    @Test
    @DisplayName("任务状态公开岗位 ID，供同 JD 简历选择使用")
    void create_exposesJobDescriptionId() {
        when(consentService.hasValidConsent(100L)).thenReturn(true);
        doNothing().when(quotaService).check(eq(100L), any());
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(taskRepository.save(any(AiTask.class))).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(1L);
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            return task;
        });

        AiTaskStatusResponse response = service.create(new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, Map.of(), null, 88L, null, null, null, null),
                "job-id-response", 100L);

        assertEquals(88L, response.jobDescriptionId());
    }

    @Test
    @DisplayName("未授权用户创建任务 → CONSENT_REQUIRED")
    void create_notConsented_throwsConsentRequired() {
        when(consentService.hasValidConsent(100L)).thenReturn(false);

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, null, null, null, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, "key", 100L));
        assertEquals(ErrorCode.CONSENT_REQUIRED, ex.getErrorCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("撤回同意后创建任务 → CONSENT_REQUIRED")
    void create_afterWithdraw_throwsConsentRequired() {
        // hasValidConsent 返回 false 模拟撤回后的状态
        when(consentService.hasValidConsent(100L)).thenReturn(false);

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.RESUME_OPTIMIZE, null, null, null, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, "key", 100L));
        assertEquals(ErrorCode.CONSENT_REQUIRED, ex.getErrorCode());
    }

    @Test
    @DisplayName("幂等: 相同 key + 相同指纹 → 返回已有任务")
    void create_sameKeySameFingerprint_returnsExisting() {
        when(consentService.hasValidConsent(100L)).thenReturn(true);
        doNothing().when(quotaService).check(eq(100L), any());

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, Map.of("key", "value"), null, null, null, null, null, null);

        // 计算与 service 内部相同的指纹
        String fingerprint = idempotencyService.fingerprint(
                Map.of("taskType", "JOB_GENERATION", "input", Map.of("key", "value")));

        AiTask existing = task(1L, 100L, AiTaskType.JOB_GENERATION, fingerprint);
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                100L, AiTaskType.JOB_GENERATION, "idem-key")).thenReturn(Optional.of(existing));

        AiTaskStatusResponse response = service.create(req, "idem-key", 100L);

        assertEquals(1L, response.id());
        assertEquals(AiTaskStatus.PENDING, response.status());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("幂等: 相同 key + 不同指纹 → CONFLICT")
    void create_sameKeyDifferentFingerprint_throwsConflict() {
        when(consentService.hasValidConsent(100L)).thenReturn(true);
        doNothing().when(quotaService).check(eq(100L), any());

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, Map.of("key", "new-value"), null, null, null, null, null, null);

        AiTask existing = task(1L, 100L, AiTaskType.JOB_GENERATION, "stale-fingerprint");
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                100L, AiTaskType.JOB_GENERATION, "idem-key")).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, "idem-key", 100L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("配额超限 → RATE_LIMITED")
    void create_quotaExceeded_throwsRateLimited() {
        when(consentService.hasValidConsent(100L)).thenReturn(true);
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "AI 任务配额已用完"))
                .when(quotaService).check(100L, AiTaskType.JOB_GENERATION);

        CreateAiTaskRequest req = new CreateAiTaskRequest(
                AiTaskType.JOB_GENERATION, null, null, null, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, "key", 100L));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    private AiTask task(Long id, Long userId, AiTaskType type, String fingerprint) {
        AiTask t = new AiTask();
        t.setId(id);
        t.setUserId(userId);
        t.setTaskType(type);
        t.setIdempotencyKey("idem-key");
        t.setRequestFingerprint(fingerprint);
        t.setInputSnapshotJson(Map.of("taskType", type.name()));
        t.setStatus(AiTaskStatus.PENDING);
        t.setRetryCount(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }
}
