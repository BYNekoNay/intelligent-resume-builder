package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.common.error.PublicFailureCopy;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.dto.ExportTaskStatusResponse;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 导出任务**重试**语义（从 {@code ExportServiceTest} 拆出）。
 *
 * <p>拆的原因：{@code ExportServiceTest} 已到测试文件行数棘轮的上限（400 行）。棘轮的口径是
 * 「拆场景类」而不是登记进基线 —— 重试是一组独立的状态机约束（谁能重试、能重试几次、
 * 重试后哪些字段被重置），和创建/下载/复用的断言没有共享夹具，拆开后两边都更易读。
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceRetryTest {

    @Mock private ExportTaskRepository exportTaskRepository;
    @Mock private ResumeVersionRepository resumeVersionRepository;
    @Mock private ExportStorageService storageService;
    @Mock private ExportExpiryService expiryService;

    private ExportService service;

    @BeforeEach
    void setUp() {
        // max-manual-retries = 5（与 application.yml 默认一致）
        service = new ExportService(exportTaskRepository, resumeVersionRepository, storageService, expiryService,
                new PublicFailureCopy(new FailureCategoryClassifier()), 24, 5);
    }

    @Test
    @DisplayName("失败路径: 非 FAILED 状态(SUCCESS/PENDING)重试返回 CONFLICT 且不落库")
    void retry_nonFailedTask_conflict() {
        ExportTask successTask = new ExportTask();
        successTask.setId(1L);
        successTask.setUserId(100L);
        successTask.setStatus(ExportStatus.SUCCESS);
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(successTask));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retry(1L, 100L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("只有失败的任务可以重试", ex.getMessage());
        verify(exportTaskRepository, never()).save(any());

        ExportTask pendingTask = new ExportTask();
        pendingTask.setId(2L);
        pendingTask.setUserId(100L);
        pendingTask.setStatus(ExportStatus.PENDING);
        when(exportTaskRepository.findByIdAndUserId(2L, 100L)).thenReturn(Optional.of(pendingTask));

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.retry(2L, 100L));
        assertEquals(ErrorCode.CONFLICT, ex2.getErrorCode());
        assertEquals("只有失败的任务可以重试", ex2.getMessage());
        verify(exportTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("正常路径: FAILED 任务重试重置为 PENDING、retryCount+1、清空错误、刷新过期时间")
    void retry_failedTask_resetsToPending() {
        LocalDateTime originalExpiresAt = LocalDateTime.now().minusDays(1);
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.FAILED);
        task.setErrorMessage("PDF render failed");
        task.setRetryCount(2);
        task.setExpiresAt(originalExpiresAt);
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));

        ExportTaskStatusResponse resp = service.retry(1L, 100L);

        assertEquals("PENDING", resp.status());
        assertNull(resp.errorMessage());
        assertNull(task.getErrorMessage());
        assertEquals(ExportStatus.PENDING, task.getStatus());
        assertEquals(3, task.getRetryCount()); // 原值 2 → +1
        assertNull(resp.downloadUrl()); // PENDING 无下载链接
        assertNotNull(task.getExpiresAt());
        assertTrue(task.getExpiresAt().isAfter(originalExpiresAt)); // 过期时间被刷新
        // 手动重试是「用户现在就要」，必须清掉容量拒绝留下的延迟重排时刻
        assertNull(task.getNextAttemptAt());
        verify(exportTaskRepository).save(task);
    }

    @Test
    @DisplayName("失败路径: 手动重试达上限（max-manual-retries=5）后拒绝，避免同一行被无限重跑")
    void retry_beyondManualLimit_conflict() {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.FAILED);
        task.setRetryCount(5);
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 100L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已重试 5 次"), "实际：" + ex.getMessage());
        verify(exportTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("失败路径: 跨用户 retry 返回 NOT_FOUND")
    void retry_crossUser_notFound() {
        when(exportTaskRepository.findByIdAndUserId(1L, 200L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retry(1L, 200L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(expiryService, storageService);
        verify(exportTaskRepository, never()).save(any());
    }
}
