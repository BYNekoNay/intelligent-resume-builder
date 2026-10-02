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
 * 数据生命周期分档清扫作业（决策 D2：阶段 1 = A 档物理删除；阶段 2 = B 档转最小快照）。
 *
 * <p>设计见 {@code docs/plans/2026-10-01-001-data-retention-tiered-purge.md}。
 * 与既有的 {@code AiTaskRetentionService}（AI 任务快照压缩）**同范式**：
 * 单轮上限、幂等可重跑、单批独立事务。
 *
 * <p><b>两档处置</b>（每轮对每个资源各取一批）：
 * <ul>
 *   <li><b>A 档 · 无任何引用者</b> → 物理删除（{@code DELETE}，带 {@code deleted_at} 二次保护）；</li>
 *   <li><b>B 档 · 存在引用者</b> → 转最小不可编辑快照（{@code UPDATE}，同样带二次保护）——
 *       不能删（会撞外键、破坏历史可读性），但也不该继续留完整 PII。快照内容见
 *       {@link RetentionSnapshot}（常量或由现有内容确定性派生 ⇒ **幂等**）。</li>
 * </ul>
 *
 * <p><b>护栏</b>（方案 §6）：
 * <ul>
 *   <li>G1 默认关闭（{@code enabled=false}）—— 未显式开启时只记一行日志即返回；</li>
 *   <li>G2 默认 dry-run —— 即使开启也只记「计划动作」，必须显式置 false 才真正写库；</li>
 *   <li>G3 单轮每资源每档最多 {@code batchSize} 行（一次调度各处理一批，不循环耗尽）；</li>
 *   <li>G4 删除前由 SQL 再判一次引用（{@code NOT EXISTS}/{@code EXISTS} 就在候选查询里），
 *       并捕获外键冲突降级跳过（TOCTOU 兜底）；</li>
 *   <li>G5 不碰 {@code user} 表（账户级联删除由 {@link AccountPurgeService} 独立实现，见 D2 阶段 3）；</li>
 *   <li>G6 可观测：汇总日志 + {@code retention_purge_scanned/purged/skipped/snapshotted} 指标；</li>
 *   <li>G7 数值配置由 {@code NumericConfigurationValidator} 在启动时校验（0/负值即拒绝）。</li>
 * </ul>
 *
 * <p><b>本作业覆盖四个资源</b>：{@code resume_version}（11 处引用）、{@code career_material}（2 处）、
 * {@code resume}（1 处，**无 B 档**：只有 {@code title}，被引用者原样保留）、{@code job_description}（8 处）。
 * 两个资源的「间接引用」担忧由分档设计本身消解：被引用的行会**原地保留**，FK 因而始终成立。
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
        int limit = Math.max(1, properties.getBatchSize());

        List<PurgeOutcome> outcomes = List.of(
                process("resume_version", cutoff, limit,
                        l -> repository.findPurgeableResumeVersions(cutoff, l), repository::deleteResumeVersion,
                        l -> repository.findSnapshottableResumeVersions(cutoff, l),
                        repository::snapshotResumeVersion),
                process("career_material", cutoff, limit,
                        l -> repository.findPurgeableCareerMaterials(cutoff, l), repository::deleteCareerMaterial,
                        l -> repository.findSnapshottableCareerMaterials(cutoff, l),
                        repository::snapshotCareerMaterial),
                // resume 只有 title、无大字段 ⇒ **没有 B 档**：被引用者原样保留即可（传空 finder/无操作）
                process("resume", cutoff, limit,
                        l -> repository.findPurgeableResumes(cutoff, l), repository::deleteResume,
                        l -> List.of(), id -> 0),
                process("job_description", cutoff, limit,
                        l -> repository.findPurgeableJobDescriptions(cutoff, l), repository::deleteJobDescription,
                        l -> repository.findSnapshottableJobDescriptions(cutoff, l),
                        repository::snapshotJobDescription));

        outcomes.forEach(this::record);

        int purged = outcomes.stream().mapToInt(PurgeOutcome::purged).sum();
        int snapshotted = outcomes.stream().mapToInt(PurgeOutcome::snapshotted).sum();
        int skipped = outcomes.stream().mapToInt(PurgeOutcome::skipped).sum();
        if (properties.isDryRun()) {
            log.info("[dry-run] retention purge scanned at cutoff {}: {}; no rows were written",
                    cutoff, describe(outcomes));
        } else if (purged > 0 || snapshotted > 0 || skipped > 0) {
            log.info("Retention purge removed {} rows and snapshotted {} rows ({}), soft-deleted before {}",
                    purged, snapshotted, describe(outcomes), cutoff);
        }
        return purged;
    }

    /** 每资源一行小计，形如 {@code resume_version[purge 2/3, snapshot 1/1]}（分子=实际、分母=候选）。 */
    private static String describe(List<PurgeOutcome> outcomes) {
        return outcomes.stream()
                .map(o -> o.resource() + "[purge " + o.purged() + "/" + o.scanned()
                        + ", snapshot " + o.snapshotted() + "/" + o.snapshotCandidates() + "]")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private void record(PurgeOutcome outcome) {
        observability.recordRetentionPurge(outcome.resource(), outcome.scanned(), outcome.purged(),
                outcome.skipped(), outcome.snapshotted());
    }

    /**
     * 单资源一轮：**A 档与 B 档各取一批**（各 {@code LIMIT batch-size}），各在一个事务内逐行处理。
     *
     * <p>与方案 §6 G3「单轮每表最多 batch-size 行」一致 —— 让每次**破坏性/写**操作的规模有界；
     * 未处理完的候选留待下次调度按 interval 逐批收敛。
     */
    private PurgeOutcome process(String resource, LocalDateTime cutoff, int limit,
                                 IntFunction<List<Long>> purgeFinder, ToIntFunction<Long> deleter,
                                 IntFunction<List<Long>> snapshotFinder, ToIntFunction<Long> snapper) {
        List<Long> purgeIds = purgeFinder.apply(limit);
        List<Long> snapIds = snapshotFinder.apply(limit);

        if (properties.isDryRun()) {
            if (!purgeIds.isEmpty()) {
                log.info("[dry-run] {}: {} row(s) would be purged (soft-deleted before {}): {}",
                        resource, purgeIds.size(), cutoff, sample(purgeIds));
            }
            if (!snapIds.isEmpty()) {
                log.info("[dry-run] {}: {} row(s) would be snapshotted (soft-deleted before {}): {}",
                        resource, snapIds.size(), cutoff, sample(snapIds));
            }
            return new PurgeOutcome(resource, purgeIds.size(), 0, 0, 0, snapIds.size());
        }

        BatchOutcome deleted = Objects.requireNonNull(
                transactionTemplate.execute(status -> deleteEach(resource, purgeIds, deleter)),
                "事务回调恒返回非空小计");
        int snapshotted = Objects.requireNonNull(
                transactionTemplate.execute(status -> snapshotEach(resource, snapIds, snapper)),
                "事务回调恒返回非空计数");

        return new PurgeOutcome(resource, purgeIds.size(), deleted.deleted(), deleted.skipped(),
                snapshotted, snapIds.size());
    }

    private static String sample(List<Long> ids) {
        return ids.size() <= 20 ? ids.toString() : ids.subList(0, 20) + "…";
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
                log.warn("{}: skip purge of row {} - it became referenced between scan and delete ({})",
                        resource, id, e.getMostSpecificCause().getMessage());
            }
        }
        return new BatchOutcome(deleted, skipped);
    }

    /** 逐行快照化。快照是「写入固定/确定性内容」，故可安全重跑（幂等）。 */
    private int snapshotEach(String resource, List<Long> ids, ToIntFunction<Long> snapper) {
        int snapshotted = 0;
        for (Long id : ids) {
            try {
                snapshotted += snapper.applyAsInt(id);
            } catch (DataIntegrityViolationException e) {
                log.warn("{}: skip snapshot of row {} - {}",
                        resource, id, e.getMostSpecificCause().getMessage());
            }
        }
        return snapshotted;
    }

    /**
     * 单资源一轮的处理结果（供汇总日志与指标使用）。
     *
     * @param scanned          A 档候选数
     * @param purged           实际删除数
     * @param skipped          因外键冲突跳过数
     * @param snapshotted      实际快照化数
     * @param snapshotCandidates B 档候选数（dry-run 时用于呈现「计划动作」）
     */
    record PurgeOutcome(String resource, int scanned, int purged, int skipped,
                        int snapshotted, int snapshotCandidates) {}

    /** 一批内的处理小计：实际删除数与因外键冲突跳过数。 */
    record BatchOutcome(int deleted, int skipped) {}
}
