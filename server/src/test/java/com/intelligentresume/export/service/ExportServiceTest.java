package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.dto.CreateExportRequest;
import com.intelligentresume.export.dto.ExportTaskStatusResponse;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * ExportService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private ExportTaskRepository exportTaskRepository;
    @Mock private ResumeVersionRepository resumeVersionRepository;
    @Mock private ExportStorageService storageService;
    @Mock private ExportExpiryService expiryService;

    private ExportService service;

    @BeforeEach
    void setUp() {
        service = new ExportService(exportTaskRepository, resumeVersionRepository, storageService, expiryService, 24);
    }

    @Test
    @DisplayName("正常路径: 创建导出返回 PENDING")
    void create_pending() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        when(exportTaskRepository.save(any())).thenAnswer(inv -> {
            ExportTask t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        CreateExportRequest req = new CreateExportRequest(1L, "classic");
        ExportTaskStatusResponse resp = service.create(req, 100L);

        assertEquals("PENDING", resp.status());
        assertEquals("classic", resp.templateCode());
        assertNull(resp.downloadUrl()); // PENDING 无下载链接
        verify(exportTaskRepository).save(any());
    }

    @Test
    @DisplayName("失败路径: templateCode 不在支持列表时返回 VALIDATION")
    void create_invalidTemplate_validationFails() {
        CreateExportRequest req = new CreateExportRequest(1L, "unknown");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    }

    @Test
    @DisplayName("正常路径: 支持编辑器提供的非 classic 模板")
    void create_supportedTemplate_pending() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        when(exportTaskRepository.save(any())).thenAnswer(invocation -> {
            ExportTask task = invocation.getArgument(0);
            task.setId(2L);
            return task;
        });

        ExportTaskStatusResponse response = service.create(new CreateExportRequest(1L, "academic"), 100L);

        assertEquals("academic", response.templateCode());
    }

    @Test
    @DisplayName("失败路径: 跨用户 create 返回 NOT_FOUND")
    void create_crossUser_notFound() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(200L); // 其他用户
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        CreateExportRequest req = new CreateExportRequest(1L, "classic");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("失败路径: 跨用户 get 返回 NOT_FOUND")
    void get_crossUser_notFound() {
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(1L, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("失败路径: 跨用户 download 返回 NOT_FOUND")
    void download_crossUser_notFound() {
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.download(1L, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(expiryService);
    }

    @Test
    @DisplayName("失败路径: 下载过期文件返回 NOT_FOUND")
    void download_expired_notFound() {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.SUCCESS);
        task.setExpiresAt(LocalDateTime.now().minusHours(1)); // 已过期
        task.setStorageKey("test-key.pdf");
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));
        when(expiryService.expireIfDue(eq(1L), any(LocalDateTime.class))).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.download(1L, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("导出文件已过期", ex.getMessage());
        verify(expiryService).expireIfDue(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("#54: 过期判定被并发推进（重试排队）时以库存最新状态为准，不误报 EXPIRED")
    void get_expiredButConcurrentlyRetried_returnsLatestState() {
        ExportTask stale = new ExportTask();
        stale.setId(1L);
        stale.setUserId(100L);
        stale.setStatus(ExportStatus.SUCCESS);
        stale.setExpiresAt(LocalDateTime.now().minusHours(1));
        ExportTask refreshed = new ExportTask();
        refreshed.setId(1L);
        refreshed.setUserId(100L);
        refreshed.setStatus(ExportStatus.PENDING);
        refreshed.setExpiresAt(LocalDateTime.now().plusHours(23));
        when(exportTaskRepository.findByIdAndUserId(1L, 100L))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(refreshed));
        when(expiryService.expireIfDue(eq(1L), any(LocalDateTime.class))).thenReturn(false);

        ExportTaskStatusResponse resp = service.get(1L, 100L);

        assertEquals("PENDING", resp.status());
    }

    @Test
    @DisplayName("#14: 同一（版本, 模板）的在途任务被复用，不重复排队")
    void create_reusesInFlightTaskForSameTuple() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        ExportTask pending = new ExportTask();
        pending.setId(5L);
        pending.setUserId(100L);
        pending.setResumeVersionId(1L);
        pending.setTemplateCode("classic");
        pending.setStatus(ExportStatus.PENDING);
        when(exportTaskRepository.findFirstByUserIdAndResumeVersionIdAndTemplateCodeAndStatusInOrderByIdDesc(
                eq(100L), eq(1L), eq("classic"), anyList())).thenReturn(Optional.of(pending));

        ExportTaskStatusResponse resp = service.create(new CreateExportRequest(1L, "classic"), 100L);

        assertEquals(5L, resp.taskId());
        assertEquals("PENDING", resp.status());
        verify(exportTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("#14: 未过期的成功结果被复用，返回下载链接")
    void create_reusesNonExpiredSuccessTask() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        ExportTask success = new ExportTask();
        success.setId(6L);
        success.setUserId(100L);
        success.setResumeVersionId(1L);
        success.setTemplateCode("classic");
        success.setStatus(ExportStatus.SUCCESS);
        success.setExpiresAt(LocalDateTime.now().plusHours(2));
        when(exportTaskRepository.findFirstByUserIdAndResumeVersionIdAndTemplateCodeAndStatusInOrderByIdDesc(
                eq(100L), eq(1L), eq("classic"), anyList())).thenReturn(Optional.of(success));

        ExportTaskStatusResponse resp = service.create(new CreateExportRequest(1L, "classic"), 100L);

        assertEquals(6L, resp.taskId());
        assertEquals("SUCCESS", resp.status());
        assertEquals("/api/exports/files/6", resp.downloadUrl());
        verify(exportTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName("#14: 已过期的成功结果不复用，重新排队渲染")
    void create_expiredSuccessTask_queuesNewTask() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        ExportTask expired = new ExportTask();
        expired.setId(7L);
        expired.setUserId(100L);
        expired.setResumeVersionId(1L);
        expired.setTemplateCode("classic");
        expired.setStatus(ExportStatus.SUCCESS);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(exportTaskRepository.findFirstByUserIdAndResumeVersionIdAndTemplateCodeAndStatusInOrderByIdDesc(
                eq(100L), eq(1L), eq("classic"), anyList())).thenReturn(Optional.of(expired));
        when(exportTaskRepository.save(any())).thenAnswer(inv -> {
            ExportTask t = inv.getArgument(0);
            t.setId(8L);
            return t;
        });

        ExportTaskStatusResponse resp = service.create(new CreateExportRequest(1L, "classic"), 100L);

        assertEquals(8L, resp.taskId());
        assertEquals("PENDING", resp.status());
        verify(exportTaskRepository).save(any());
    }

    @Test
    @DisplayName("失败路径: 下载失败任务返回 NOT_FOUND")
    void download_failed_notFound() {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.FAILED);
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.download(1L, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("正常路径: 下载成功任务返回 PDF 字节")
    void download_success_returnsPdf() {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.SUCCESS);
        task.setExpiresAt(LocalDateTime.now().plusHours(1));
        task.setStorageKey("test-key.pdf");
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));
        when(storageService.read("test-key.pdf")).thenReturn("PDF bytes".getBytes());

        Resource resource = service.download(1L, 100L);
        assertNotNull(resource);
    }

    @Test
    @DisplayName("正常路径: get 过期任务自动标记 EXPIRED")
    void get_expiredTask_marksExpired() {
        ExportTask task = new ExportTask();
        task.setId(1L);
        task.setUserId(100L);
        task.setStatus(ExportStatus.SUCCESS);
        task.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(exportTaskRepository.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(task));
        when(expiryService.expireIfDue(eq(1L), any(LocalDateTime.class))).thenReturn(true);

        ExportTaskStatusResponse resp = service.get(1L, 100L);
        assertEquals("EXPIRED", resp.status());
        verify(expiryService).expireIfDue(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("失败路径: 归档版本不可导出 —— 409 且提示先恢复（与 ATS/评分/面试一致）")
    void create_archivedVersion_conflict() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(100L);
        version.setDeletedAt(LocalDateTime.now()); // 已归档
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        CreateExportRequest req = new CreateExportRequest(1L, "classic");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.VERSION_ARCHIVED, ex.getErrorCode());
        assertEquals("该简历版本已归档，请先恢复后再发起导出", ex.getMessage());
    }

    @Test
    @DisplayName("失败路径: 归档且不属于当前用户仍返回 40401（不泄露归档状态）")
    void create_archivedForeignVersion_notFound() {
        ResumeVersion version = new ResumeVersion();
        version.setId(1L);
        version.setCreatedBy(200L); // 他人
        version.setDeletedAt(LocalDateTime.now());
        when(resumeVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        CreateExportRequest req = new CreateExportRequest(1L, "classic");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("简历版本不存在", ex.getMessage());
    }

    @Test
    @DisplayName("失败路径: 非 FAILED 状态(SUCCESS/PENDING)重试返回 CONFLICT 且不落库")
    void retry_nonFailedTask_conflict() {
        // SUCCESS 任务
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

        // PENDING 任务
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
        verify(exportTaskRepository).save(task);
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
