package com.intelligentresume.retention;

import com.intelligentresume.common.observability.AppObservability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账户清扫作业的编排与护栏（决策 D2 阶段 3）：
 * 关闭状态零交互、到期任务级联删除并标 SUCCESS、单任务失败转 PARTIAL_FAILED 不牵连同批、
 * 候选查询按批量与截止时间发起。
 */
class AccountPurgeServiceTest {

    private final AccountDeletionJobRepository jobRepository = mock(AccountDeletionJobRepository.class);
    private final AccountPurgeRepository purgeRepository = mock(AccountPurgeRepository.class);
    private final AppObservability observability = mock(AppObservability.class);

    private AccountPurgeService service(boolean enabled) {
        AccountPurgeProperties properties = new AccountPurgeProperties();
        properties.setEnabled(enabled);
        properties.setBatchSize(20);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        return new AccountPurgeService(jobRepository, purgeRepository, properties, observability, transactionManager);
    }

    private AccountDeletionJob job(long id, long userId, AccountDeletionJob.Status status, LocalDateTime cancelUntil) {
        AccountDeletionJob job = new AccountDeletionJob();
        job.setUserId(userId);
        job.setStatus(status);
        job.setRequestedAt(cancelUntil.minusDays(7));
        job.setCancelUntil(cancelUntil);
        try {
            var field = AccountDeletionJob.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return job;
    }

    @Test
    @DisplayName("G1：默认关闭时不查候选、不删任何数据")
    void disabled_skipsEverything() {
        int result = service(false).purgeExpiredAccounts();
        assertThat(result).isZero();
        verifyNoInteractions(jobRepository, purgeRepository);
    }

    @Test
    @DisplayName("到期任务：级联删除全部业务数据 + user 行，任务转 SUCCESS")
    void dueJob_isPurgedAndMarkedSuccess() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        AccountDeletionJob job = job(11L, 42L, AccountDeletionJob.Status.PENDING, past);
        when(jobRepository.findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(job));

        int result = service(true).purgeExpiredAccounts();

        assertThat(result).isEqualTo(1);
        verify(purgeRepository).purgeUserData(42L);
        verify(purgeRepository).deleteUser(42L);
        ArgumentCaptor<AccountDeletionJob> saved = ArgumentCaptor.forClass(AccountDeletionJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AccountDeletionJob.Status.SUCCESS);
        assertThat(saved.getValue().getCompletedAt()).isNotNull();
        verify(observability).recordAccountPurge(1, 0);
    }

    @Test
    @DisplayName("单任务失败转 PARTIAL_FAILED，同批其它任务照常处理（互不牵连）")
    void failure_isIsolatedAndRetryable() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        AccountDeletionJob failing = job(11L, 42L, AccountDeletionJob.Status.PENDING, past);
        AccountDeletionJob healthy = job(12L, 43L, AccountDeletionJob.Status.PENDING, past);
        when(jobRepository.findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(failing, healthy));
        when(jobRepository.findById(11L)).thenReturn(Optional.of(failing));
        org.mockito.Mockito.doThrow(new QueryTimeoutException("db gone"))
                .when(purgeRepository).purgeUserData(42L);

        int result = service(true).purgeExpiredAccounts();

        assertThat(result).isEqualTo(1);
        verify(purgeRepository).purgeUserData(43L);
        verify(purgeRepository).deleteUser(43L);
        ArgumentCaptor<AccountDeletionJob> saved = ArgumentCaptor.forClass(AccountDeletionJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(j ->
                assertThat(j.getStatus()).isEqualTo(AccountDeletionJob.Status.PARTIAL_FAILED));
        verify(observability).recordAccountPurge(1, 1);
    }

    @Test
    @DisplayName("无到期候选时不触发任何删除，也不记非零指标")
    void noCandidates_isNoOp() {
        when(jobRepository.findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        int result = service(true).purgeExpiredAccounts();

        assertThat(result).isZero();
        verify(purgeRepository, never()).purgeUserData(anyLong());
        verify(purgeRepository, never()).deleteUser(anyLong());
        verify(observability).recordAccountPurge(0, 0);
    }

    @Test
    @DisplayName("候选查询只取 PENDING 与 PARTIAL_FAILED（CANCELLED/SUCCESS 永不重入）")
    void candidates_onlyPendingOrPartialFailed() {
        when(jobRepository.findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        service(true).purgeExpiredAccounts();
        var statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(
                statusesCaptor.capture(), any(), any(Pageable.class));
        assertThat(statusesCaptor.getValue()).containsExactly(
                AccountDeletionJob.Status.PENDING, AccountDeletionJob.Status.PARTIAL_FAILED);
    }
}
