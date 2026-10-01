package com.intelligentresume.retention;

import com.intelligentresume.common.observability.AppObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * 数据生命周期分档清扫作业（决策 D2 阶段 1：**无引用资源**按期硬删）。
 *
 * <p>设计见 {@code docs/plans/2026-10-01-001-data-retention-tiered-purge.md}。
 * 与既有的 {@code AiTaskRetentionService}（AI 任务快照压缩）**同范式**：
 * 单轮上限、幂等可重跑、单批独立事务。
 *
 * <p><b>护栏</b>（方案 §6）：
 * <ul>
 *   <li>G1 默认关闭（{@code enabled=false}）—— 未显式开启时只记一行日志即返回；</li>
 *   <li>G2 默认 dry-run —— 即使开启也只记「计划动作」，必须显式置 false 才真正删除；</li>
 *   <li>G3 单轮每资源最多 {@code batchSize} 行（**一次调度只处理一批**，见 {@link #purgeResource}）；</li>
 *   <li>G4 删除前由 SQL 再判一次引用（{@code NOT EXISTS} 就在 DELETE 的候选查询里），
 *       并捕获外键冲突降级跳过（TOCTOU 兜底）；</li>
 *   <li>G5 不碰 {@code user} 表（账户侧留待阶段 3）；</li>
 *   <li>G6 可观测：汇总日志 + {@code retention_purge_scanned/purged/skipped} 指标
 *       （{@code snapshotted} 属阶段 2）；</li>
 *   <li>G7 数值配置由 {@code NumericConfigurationValidator} 在启动时校验（0/负值即拒绝）。</li>
 * </ul>
 *
 * <p><b>阶段 1 仅覆盖两个资源</b>：{@code resume_version}（11 处引用，最复杂）与
 * {@code career_material}（2 处引用）。{@code resume} / {@code job_description} 按同一范式扩展。
 * 「被引用者转最小快照」属**阶段 2**，尚未实现。
 */
@Component
public class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final RetentionPurgeRepository repository;
    private final RetentionPurgeProperties properties;
    private final AppObservability observability;
    private final TransactionTemplate transactionTemplate;

    public RetentionPurgeService(RetentionPurgeRepository repository,
                                 RetentionPurgeProperties properties,
                                 AppObservability observability,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.observability = observability;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** @return 本次调度**实际物理删除**的行数（dry-run 与关闭状态恒为 0）。 */
    @Scheduled(fixedDelayString = "${app.retention.purge.interval-ms:21600000}")
    public int purgeExpiredSoftDeleted() {
        if (!properties.isEnabled()) {
            log.info("Retention purge disabled (app.retention.purge.enabled=false); skipped");
            return 0;
        }
        LocalDateTime cutoff = properties.cutoffFrom(LocalDateTime.now());

        PurgeOutcome versions = purgeResource("resume_version", cutoff,
                limit -> repository.findPurgeableResumeVersions(cutoff, limit), repository::deleteResumeVersion);
        PurgeOutcome materials = purgeResource("career_material", cutoff,
                limit -> repository.findPurgeableCareerMaterials(cutoff, limit), repository::deleteCareerMaterial);

        record(versions);
        record(materials);

        int total = versions.purged() + materials.purged();
        if (properties.isDryRun()) {
            log.info("[dry-run] retention purge scanned at cutoff {}: would purge resume_version={} (candidates {}), "
                            + "career_material={} (candidates {}); no rows were written",
                    cutoff, versions.purged(), versions.scanned(), materials.purged(), materials.scanned());
        } else if (total > 0 || versions.skipped() > 0 || materials.skipped() > 0) {
            log.info("Retention purge removed {} rows (resume_version={}, career_material={}) soft-deleted before {}",
                    total, versions.purged(), materials.purged(), cutoff);
        }
        return total;
    }

    private void record(PurgeOutcome outcome) {
        observability.recordRetentionPurge(outcome.resource(), outcome.scanned(), outcome.purged(), outcome.skipped());
    }

    /**
     * 单资源清扫：**一次调度只处理一批**（{@code LIMIT batch-size}），批内逐行删除，整批一个事务。
     *
     * <p>与方案 §6 G3「单轮每表最多 batch-size 行」一致 —— 目的是让每次**破坏性**操作的规模有界、
     * 可预期；未处理完的候选留待下次调度按 interval 逐批收敛（不追求在单次运行内耗尽积压）。
     */
    private PurgeOutcome purgeResource(String resource, LocalDateTime cutoff,
                                       IntFunction<List<Long>> finder,
                                       ToIntFunction<Long> deleter) {
        int limit = Math.max(1, properties.getBatchSize());
        List<Long> ids = finder.apply(limit);
        if (ids.isEmpty()) {
            return new PurgeOutcome(resource, 0, 0, 0);
        }
        if (properties.isDryRun()) {
            log.info("[dry-run] {}: {} candidate row(s) would be purged (soft-deleted before {}): {}",
                    resource, ids.size(), cutoff, ids.size() <= 20 ? ids : ids.subList(0, 20) + "…");
            return new PurgeOutcome(resource, ids.size(), 0, 0);
        }
        BatchOutcome batch = Objects.requireNonNull(
                transactionTemplate.execute(status -> deleteEach(resource, ids, deleter)),
                "事务回调恒返回非空小计");
        return new PurgeOutcome(resource, ids.size(), batch.deleted(), batch.skipped());
    }

    private BatchOutcome deleteEach(String resource, List<Long> ids, ToIntFunction<Long> deleter) {
        int deleted = 0;
        int skipped = 0;
        for (Long id : ids) {
            try {
                deleted += deleter.applyAsInt(id);
            } catch (DataIntegrityViolationException e) {
                // TOCTOU 兜底：扫描后、删除前被重新引用 —— 保留该行，下次调度再判
                skipped++;
                log.warn("{}: skip row {} - it became referenced between scan and delete ({})",
                        resource, id, e.getMostSpecificCause().getMessage());
            }
        }
        return new BatchOutcome(deleted, skipped);
    }

    /** 单资源一次调度的处理结果（供汇总日志与指标使用）。 */
    record PurgeOutcome(String resource, int scanned, int purged, int skipped) {}

    /** 一批内的处理小计：实际删除数与因外键冲突跳过数。 */
    record BatchOutcome(int deleted, int skipped) {}
}
