package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 任务仓储 —— 任务的全部数据库读写都从这里走:既有常规查询,也承载工作器的租约原生 SQL。
 *
 * <p>【业务问题】除了普通的按用户查任务，多个应用实例同时抢任务时必须有数据库级的互斥手段，
 * 这组"租约 SQL"（条件 UPDATE 抢占，租约 = worker 领任务后的一段时间使用权，超时未完成会被收回）
 * 就是那条防线。</p>
 *
 * <p>【链路位置】{@code AiTaskService} 用它做创建/查询/删除；
 * {@code TaskLeaseService} 用 {@code claimableTasksByTypes}/{@code acquireLease}/
 * {@code renewLease}/{@code abandonExhausted} 实现领取、续租与崩溃收口；
 * {@code AiQuotaService} 用聚合查询数配额；{@code AiTaskRetentionService} 用它找清理候选。</p>
 *
 * <p>【改坏它会怎样】租约 SQL 的条件被削弱会让两个实例同时执行同一任务（重复调用模型、重复计费）；
 * 配额聚合口径与 {@code AiQuotaService} 不一致会出现"消耗和限额不可比"；
 * 忘了排除待确认任务会让用户的确认结果被清理或删除。</p>
 *
 * <p>要点 / 红线:</p>
 * <ul>
 *   <li>租约相关方法({@code claimableTasksByTypes} / {@code acquireLease} / {@code renewLease} /
 *       {@code abandonExhausted})是跨实例互斥的唯一防线:条件更新返回 0 表示没抢到,
 *       调用方必须据此跳过该任务,不能假设"查到了就归我"。</li>
 *   <li>{@code acquireLease} 会顺带把 {@code retry_count} +1,这个计数同时被每日配额和
 *       {@code abandonExhausted} 使用;改其中一个必须同时确认另一个。</li>
 *   <li>配额聚合类查询用 {@code retry_count < 1 ? 1 : retry_count} 计"尝试数",
 *       与 {@code AiQuotaService#check} 的口径必须字面一致,否则"消耗"和"限额"不可比。</li>
 *   <li>涉及"待确认"的查询都显式排除 {@code confirmationStatus = PENDING}:
 *       那是用户还没处理完的工作,不能被清理或删除。</li>
 *   <li>部分查询走 JPQL,部分走原生 SQL(为了 {@code FOR UPDATE} / {@code LIMIT})。
 *       原生 SQL 里的枚举值写成字面量,新增枚举值时要回来检查这些字符串。</li>
 * </ul>
 */
public interface AiTaskRepository extends JpaRepository<AiTask, Long> {

    Optional<AiTask> findByIdAndUserId(Long id, Long userId);

    /**
     * SELECT FOR UPDATE：锁定任务行，防止并发确认。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AiTask t WHERE t.id = :id")
    Optional<AiTask> findByIdForUpdate(@Param("id") Long id);

    Optional<AiTask> findByUserIdAndTaskTypeAndIdempotencyKey(Long userId, AiTaskType taskType, String idempotencyKey);

    /**
     * 续办列表读模型（ideation #52）：只投影首页恢复入口需要的元数据，
     * 不读 {@code resultJson} / {@code inputSnapshotJson}（生成结果可达 64KB/任务，
     * 首页只消费 id/类型/状态/时间）。
     */
    interface ContinuationProjection {
        Long getId();
        AiTaskType getTaskType();
        Long getParentTaskId();
        AiTaskStatus getStatus();
        ConfirmationStatus getConfirmationStatus();
        Long getResultResumeVersionId();
        Integer getRetryCount();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }

    @Query("""
            SELECT t.id AS id, t.taskType AS taskType, t.parentTaskId AS parentTaskId,
                   t.status AS status, t.confirmationStatus AS confirmationStatus,
                   t.resultResumeVersionId AS resultResumeVersionId, t.retryCount AS retryCount,
                   t.createdAt AS createdAt, t.updatedAt AS updatedAt
            FROM AiTask t
            WHERE t.userId = :userId
              AND t.taskType IN (com.intelligentresume.ai.task.domain.AiTaskType.JOB_MATERIAL_SELECTION,
                                 com.intelligentresume.ai.task.domain.AiTaskType.JOB_GENERATION)
              AND (t.status IN (com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING,
                                com.intelligentresume.ai.task.domain.AiTaskStatus.RUNNING)
                   OR (t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.SUCCESS
                       AND t.confirmationStatus = com.intelligentresume.ai.task.domain.ConfirmationStatus.PENDING))
            ORDER BY t.updatedAt DESC, t.id DESC
            """)
    List<ContinuationProjection> findContinuationRowsByUserId(@Param("userId") Long userId);

    long countByUserIdAndTaskTypeAndCreatedAtAfter(Long userId, AiTaskType taskType, LocalDateTime after);

    @Modifying
    @Query("UPDATE AiTask t SET t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.CANCELLED, " +
            "t.errorMessage = :message, t.leaseOwner = null, t.leaseExpiresAt = null, t.updatedAt = :now " +
            "WHERE t.userId = :userId AND (t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING " +
            "OR t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.RUNNING)")
    int cancelActiveByUserId(@Param("userId") Long userId,
                             @Param("message") String message,
                             @Param("now") LocalDateTime now);

    /**
     * 某用户某任务类型在 {@code after} 之后的「尝试数」—— 每日配额的判定依据。
     *
     * <p>口径:retryCount 为 0 的行算 1 次,否则按 retryCount 算。也就是说被失败重试过的任务
     * 会按实际执行次数计入消耗,而不是只算 1 次。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.retryCount < 1 THEN 1 ELSE t.retryCount END), 0) FROM AiTask t " +
            "WHERE t.userId = :userId AND t.taskType = :taskType AND t.createdAt > :after")
    long countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(@Param("userId") Long userId,
                                                           @Param("taskType") AiTaskType taskType,
                                                           @Param("after") LocalDateTime after);

    /**
     * 全站当日「尝试数」（ideation #524）：与每日配额限流同一口径（重试计次），
     * 供观测 gauge 使用；不按用户维度（gauge 不能引入用户标签）。
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.retryCount < 1 THEN 1 ELSE t.retryCount END), 0) FROM AiTask t " +
            "WHERE t.taskType = :taskType AND t.createdAt > :after")
    long countAttemptsByTaskTypeAndCreatedAtAfter(@Param("taskType") AiTaskType taskType,
                                                  @Param("after") LocalDateTime after);

    /**
     * 全站当日「尝试数」按任务类型分组（一次查询取回全部类型）。
     *
     * <p>为什么需要它：观测 gauge 若按类型各发一条单类型聚合，8 个任务类型就是**每次抓取 8 条
     * {@code count} 聚合**（15~30s 抓取间隔下约 2~4 万次/天）。这台宿主机只有 3.6G 且与另一个
     * 项目共用，监控不该成为负载来源。分组查询 + 短 TTL 快照把开销压到「每个抓取周期最多 1 条」。
     *
     * <p>口径与 {@link #countAttemptsByTaskTypeAndCreatedAtAfter} 完全一致（重试计次），
     * 否则「消耗量」与「限额」不可比。
     */
    @Query("SELECT t.taskType, COALESCE(SUM(CASE WHEN t.retryCount < 1 THEN 1 ELSE t.retryCount END), 0) FROM AiTask t " +
            "WHERE t.createdAt > :after GROUP BY t.taskType")
    List<Object[]> sumAttemptsGroupedByTaskType(@Param("after") LocalDateTime after);

    /**
     * 保留期清理候选（ideation #26）：终态、非待确认、尚未压缩过的老任务。
     *
     * <p>待确认（SUCCESS + confirmationStatus=PENDING）任务属于「用户还没处理完」的工作，
     * 即使超期也不压缩，否则确认页会读到空结果。
     */
    @Query("""
            SELECT t FROM AiTask t
            WHERE t.snapshotPurged = false
              AND t.status IN (com.intelligentresume.ai.task.domain.AiTaskStatus.SUCCESS,
                               com.intelligentresume.ai.task.domain.AiTaskStatus.FAILED,
                               com.intelligentresume.ai.task.domain.AiTaskStatus.CANCELLED)
              AND (t.confirmationStatus IS NULL
                   OR t.confirmationStatus <> com.intelligentresume.ai.task.domain.ConfirmationStatus.PENDING)
              AND t.updatedAt < :cutoff
            ORDER BY t.id ASC
            """)
    List<AiTask> findPurgeableForRetention(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * 用户侧「清空 AI 任务历史」（ideation #26）：只删终态且非待确认的任务；
     * 进行中（PENDING/RUNNING）与待确认任务保留，避免影响工作器与确认流程。
     */
    @Modifying
    @Query("""
            DELETE FROM AiTask t
            WHERE t.userId = :userId
              AND t.status IN (com.intelligentresume.ai.task.domain.AiTaskStatus.SUCCESS,
                               com.intelligentresume.ai.task.domain.AiTaskStatus.FAILED,
                               com.intelligentresume.ai.task.domain.AiTaskStatus.CANCELLED)
              AND (t.confirmationStatus IS NULL
                   OR t.confirmationStatus <> com.intelligentresume.ai.task.domain.ConfirmationStatus.PENDING)
            """)
    int deleteTerminalHistoryByUserId(@Param("userId") Long userId);

    long countByStatus(AiTaskStatus status);

    @Query("SELECT MIN(t.createdAt) FROM AiTask t WHERE t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING")
    LocalDateTime findOldestPendingCreatedAt();

    /**
     * 查询指定任务类型集合中可领取的任务:PENDING 或租约过期的 RUNNING。
     *
     * <p>{@code FOR UPDATE} 仅用于多实例之间的互斥;进程内的领取始终在单个调度
     * 线程上串行发生,不并发调用。重复领取的实际防线是 {@link #acquireLease}
     * 的条件更新,因此这里不需要 {@code SKIP LOCKED}(H2 测试库也不支持该语法)。</p>
     */
    @Query(value = "SELECT * FROM ai_task WHERE (status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW())) "
            + "AND task_type IN (:taskTypes) ORDER BY id ASC LIMIT :batchSize FOR UPDATE", nativeQuery = true)
    List<AiTask> claimableTasksByTypes(@Param("taskTypes") List<String> taskTypes,
                                       @Param("batchSize") int batchSize);

    /**
     * 原子性获取租约:仅当任务仍为 PENDING 或租约过期的 RUNNING 时更新。
     * 返回受影响行数(1 = 成功获取,0 = 已被其他实例抢占)。
     */
    @Modifying
    @Query(value = "UPDATE ai_task SET status = 'RUNNING', lease_owner = :owner, lease_expires_at = :leaseUntil, retry_count = retry_count + 1, updated_at = NOW() WHERE id = :id AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()))", nativeQuery = true)
    int acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying
    @Query("UPDATE AiTask t SET t.leaseExpiresAt = :leaseUntil WHERE t.id = :id " +
            "AND t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.RUNNING AND t.leaseOwner = :owner")
    int renewLease(@Param("id") Long id, @Param("owner") String owner,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 崩溃循环收口：把「反复被领取却始终没进入终态」的任务判为 FAILED。
     *
     * <p>触发场景是进程被硬杀（OOM / kill -9 / 部署重启）：任务停在 RUNNING，
     * 租约到期后被重新领取，{@code retry_count} 每次 +1 而状态永远不落地 ——
     * 一次坏输入就能让 worker 无限重跑。<b>注意这与 R33「不自动重试」不冲突</b>：
     * 这里判定的不是「业务失败后重试」，而是「连续 N 次被领取、一次都没能收尾」，
     * 属于不可恢复的中断，必须终结掉否则永远占着队列。
     *
     * @return 1 = 已置 FAILED；0 = 条件不再成立（已被其它实例推进）
     */
    @Modifying
    @Query(value = "UPDATE ai_task SET status = 'FAILED', error_message = :message, lease_owner = NULL, "
            + "lease_expires_at = NULL, updated_at = NOW() "
            + "WHERE id = :id AND retry_count >= :maxAttempts AND (status = 'PENDING' OR status = 'RUNNING')",
            nativeQuery = true)
    int abandonExhausted(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
                         @Param("message") String message);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AiTask t WHERE t.id = :id " +
            "AND t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.RUNNING AND t.leaseOwner = :owner")
    Optional<AiTask> findRunningByIdAndOwnerForUpdate(@Param("id") Long id, @Param("owner") String owner);
}
