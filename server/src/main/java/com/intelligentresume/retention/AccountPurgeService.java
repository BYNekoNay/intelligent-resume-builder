package com.intelligentresume.retention;

import com.intelligentresume.common.observability.AppObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 账户清扫作业（决策 D2 阶段 3）：把撤销窗口已结束的删号任务落成**物理删除**。
 *
 * <p>与 {@link RetentionPurgeService}（分档清扫，阶段 1/2）同范式但语义不同：账户删除没有
 * 「被引用者保留」分支 —— 引用链整体同属被删用户，级联硬删全部数据 + {@code user} 行本身。
 *
 * <p>护栏（延续阶段 1 的 G 编号）：
 * <ul>
 *   <li>G1 {@code enabled} 默认 false —— 未显式开启时只记一行日志即返回；</li>
 *   <li>G3 单轮最多 {@code batchSize} 个任务（一个任务 = 一个用户的全量级联删除），不循环耗尽；</li>
 *   <li>每任务独立事务：要么该用户数据全部消失（含 user 行），要么整体保留 —— 不留半删状态；
 *       全部语句幂等 ⇒ PARTIAL_FAILED 的任务下轮安全重入；</li>
 *   <li>只处理 {@code cancel_until <= now} 的 PENDING 任务 —— 撤销窗口内绝不触碰；</li>
 *   <li>可观测：{@code retention_account_purge_total}{outcome=success|failed} 计数 + 汇总日志。</li>
 * </ul>
 *
 * <p>「7 天撤销窗口 + 窗口结束后 30 天内完成清理」的承诺（docs/04 §7.1）：窗口由
 * {@code cancel_until = requested_at + grace-days} 承载，作业每 6 小时一轮 —— 窗口结束后
 * 下一轮即清，远早于 30 天上限。
 */
@Component
public class AccountPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    private final AccountDeletionJobRepository jobRepository;
    private final AccountPurgeRepository purgeRepository;
    private final AccountPurgeProperties properties;
    private final AppObservability observability;
    private final TransactionTemplate transactionTemplate;

    public AccountPurgeService(AccountDeletionJobRepository jobRepository,
                               AccountPurgeRepository purgeRepository,
                               AccountPurgeProperties properties,
                               AppObservability observability,
                               PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.purgeRepository = purgeRepository;
        this.properties = properties;
        this.observability = observability;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** @return 本次调度实际完成清理的任务数（关闭状态恒为 0）。 */
    @Scheduled(fixedDelayString = "${app.retention.account-purge.interval-ms:21600000}")
    public int purgeExpiredAccounts() {
        if (!properties.isEnabled()) {
            log.info("Account purge disabled (app.retention.account-purge.enabled=false); skipped");
            return 0;
        }
        List<AccountDeletionJob> due = jobRepository.findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(
                List.of(AccountDeletionJob.Status.PENDING, AccountDeletionJob.Status.PARTIAL_FAILED),
                LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, properties.getBatchSize())));

        int succeeded = 0;
        int failed = 0;
        for (AccountDeletionJob job : due) {
            job.setStatus(AccountDeletionJob.Status.RUNNING);
            try {
                // 回调返回 Boolean.TRUE 作为结果（TransactionTemplate 允许返回 null，
                // 但显式非空返回值让「事务真的执行了」可断言 —— 不用 null 哨兵）
                transactionTemplate.execute(status -> {
                    purgeRepository.purgeUserData(job.getUserId());
                    // 成功审计行要在 user 行删除**之前**落库（本行不挂 FK，user 删除后独立保留）
                    job.setStatus(AccountDeletionJob.Status.SUCCESS);
                    job.setCompletedAt(LocalDateTime.now());
                    jobRepository.save(job);
                    purgeRepository.deleteUser(job.getUserId());
                    return Boolean.TRUE;
                });
                succeeded++;
                log.info("Account purge completed for user {} (job {})", job.getUserId(), job.getId());
            } catch (RuntimeException e) {
                // 单任务失败不牵连同批其它任务：PARTIAL_FAILED 留待下轮重入（语句幂等）
                failed++;
                AccountDeletionJob reload = jobRepository.findById(job.getId()).orElse(job);
                reload.setStatus(AccountDeletionJob.Status.PARTIAL_FAILED);
                jobRepository.save(reload);
                log.error("Account purge failed for user {} (job {}): {}", job.getUserId(), job.getId(),
                        e.getMessage());
            }
        }
        observability.recordAccountPurge(succeeded, failed);
        if (succeeded > 0 || failed > 0) {
            log.info("Account purge round: {} succeeded, {} failed (candidates {})",
                    succeeded, failed, due.size());
        }
        return succeeded;
    }
}
