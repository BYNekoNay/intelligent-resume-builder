package com.intelligentresume.retention;

import com.intelligentresume.common.observability.AppObservability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分档清扫作业的**护栏与编排**测试（决策 D2 阶段 1）。
 *
 * <p>只用 Mockito 驱动仓库，聚焦四件事：两条默认安全护栏是否真的生效、A 档是否真删、
 * **单轮是否只处理一批**（方案 §6 G3 / §8.6），以及 TOCTOU 冲突是否被安全跳过并计入 skipped 指标。
 * **SQL 本身的正确性**由 {@link RetentionPurgeRepositorySchemaTest} 在真实 schema 上冒烟
 * （那是手写 SQL 的主要风险）。
 */
class RetentionPurgeServiceTest {

    private RetentionPurgeRepository repository;
    private RetentionPurgeProperties properties;
    private AppObservability observability;
    private RetentionPurgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(RetentionPurgeRepository.class);
        observability = mock(AppObservability.class);
        properties = new RetentionPurgeProperties();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RetentionPurgeService(repository, properties, observability, transactionManager);
    }

    @Test
    @DisplayName("护栏 G1：默认关闭 —— 不查库、不删除、不记指标，返回 0")
    void disabledByDefault_doesNothing() {
        assertFalse(properties.isEnabled(), "enabled 默认必须是 false（部署后不得自动删数据）");

        assertEquals(0, service.purgeExpiredSoftDeleted());

        verifyNoInteractions(repository);
        verifyNoInteractions(observability);
    }

    @Test
    @DisplayName("护栏 G2：开启但默认 dry-run —— 只扫描、不删除")
    void dryRun_doesNotDelete() {
        properties.setEnabled(true);
        assertTrue(properties.isDryRun(), "dry-run 默认必须是 true");
        when(repository.findPurgeableResumeVersions(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(repository.findPurgeableCareerMaterials(any(), anyInt())).thenReturn(List.of());

        assertEquals(0, service.purgeExpiredSoftDeleted(), "dry-run 不得计入已删除");

        verify(repository, never()).deleteResumeVersion(anyLong());
        verify(repository, never()).deleteCareerMaterial(anyLong());
        // 指标仍记录候选量（scanned），但不记已删除
        verify(observability).recordRetentionPurge("resume_version", 2, 0, 0);
        verify(observability).recordRetentionPurge("career_material", 0, 0, 0);
    }

    @Test
    @DisplayName("A 档：无引用且超期 —— 真正物理删除并计入 purged")
    void unreferencedAndExpired_isDeleted() {
        properties.setEnabled(true);
        properties.setDryRun(false);
        when(repository.findPurgeableResumeVersions(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(repository.findPurgeableCareerMaterials(any(), anyInt())).thenReturn(List.of());
        when(repository.deleteResumeVersion(anyLong())).thenReturn(1);

        assertEquals(2, service.purgeExpiredSoftDeleted());

        verify(repository).deleteResumeVersion(1L);
        verify(repository).deleteResumeVersion(2L);
        verify(observability).recordRetentionPurge("resume_version", 2, 2, 0);
    }

    @Test
    @DisplayName("护栏 G3：一次调度只处理一批（LIMIT = batch-size），不循环耗尽积压")
    void singleRunProcessesOnlyOneBatch() {
        properties.setEnabled(true);
        properties.setDryRun(false);
        properties.setBatchSize(1);
        // 数据库按 LIMIT 1 返回一行；此处断言的是「find 只被调用一次且带 limit=1」——
        // 若实现改成循环耗尽，find 会被反复调用，本用例即红。
        when(repository.findPurgeableResumeVersions(any(), eq(1))).thenReturn(List.of(7L));
        when(repository.findPurgeableCareerMaterials(any(), eq(1))).thenReturn(List.of());
        when(repository.deleteResumeVersion(7L)).thenReturn(1);

        assertEquals(1, service.purgeExpiredSoftDeleted());

        verify(repository, times(1)).findPurgeableResumeVersions(any(), eq(1));
        verify(repository, times(1)).findPurgeableCareerMaterials(any(), eq(1));
        verify(repository).deleteResumeVersion(7L);
        verify(observability).recordRetentionPurge("resume_version", 1, 1, 0);
    }

    @Test
    @DisplayName("护栏 G4：扫描后变为被引用（外键冲突）—— 跳过、不抛异常、计入 skipped")
    void becameReferenced_isSkippedSilently() {
        properties.setEnabled(true);
        properties.setDryRun(false);
        when(repository.findPurgeableResumeVersions(any(), anyInt())).thenReturn(List.of(7L));
        when(repository.findPurgeableCareerMaterials(any(), anyInt())).thenReturn(List.of());
        when(repository.deleteResumeVersion(7L)).thenThrow(new DataIntegrityViolationException("fk violated"));

        assertDoesNotThrow(() -> assertEquals(0, service.purgeExpiredSoftDeleted()),
                "外键冲突必须被吞掉并跳过，不能中断整批");

        verify(observability).recordRetentionPurge("resume_version", 1, 0, 1);
    }

    @Test
    @DisplayName("截止时间 = now - (恢复期 + 宽限期)")
    void cutoffIsRecoveryPlusGrace() {
        properties.setRecoveryDays(30);
        properties.setGraceDays(7);
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0);

        assertEquals(now.minusDays(37), properties.cutoffFrom(now));
    }
}
