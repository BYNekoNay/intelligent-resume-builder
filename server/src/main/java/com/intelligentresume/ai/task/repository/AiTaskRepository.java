package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 任务仓储。包含工作器租约相关的原生 SQL。
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

    @Query("SELECT COALESCE(SUM(CASE WHEN t.retryCount < 1 THEN 1 ELSE t.retryCount END), 0) FROM AiTask t " +
            "WHERE t.userId = :userId AND t.taskType = :taskType AND t.createdAt > :after")
    long countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(@Param("userId") Long userId,
                                                           @Param("taskType") AiTaskType taskType,
                                                           @Param("after") LocalDateTime after);

    long countByTaskTypeAndCreatedAtAfter(AiTaskType taskType, LocalDateTime after);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AiTask t WHERE t.id = :id " +
            "AND t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.RUNNING AND t.leaseOwner = :owner")
    Optional<AiTask> findRunningByIdAndOwnerForUpdate(@Param("id") Long id, @Param("owner") String owner);
}
