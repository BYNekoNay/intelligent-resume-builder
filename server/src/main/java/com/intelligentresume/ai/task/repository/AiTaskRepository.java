package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
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

    long countByUserIdAndTaskTypeAndCreatedAtAfter(Long userId, AiTaskType taskType, LocalDateTime after);

    long countByTaskTypeAndCreatedAtAfter(AiTaskType taskType, LocalDateTime after);

    long countByStatus(AiTaskStatus status);

    @Query("SELECT MIN(t.createdAt) FROM AiTask t WHERE t.status = com.intelligentresume.ai.task.domain.AiTaskStatus.PENDING")
    LocalDateTime findOldestPendingCreatedAt();

    /**
     * 查询可领取的任务:PENDING 或租约过期的 RUNNING。
     * FOR UPDATE SKIP LOCKED 防止多实例重复领取。
     */
    @Query(value = "SELECT * FROM ai_task WHERE status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()) ORDER BY id ASC LIMIT :batchSize FOR UPDATE", nativeQuery = true)
    List<AiTask> claimableTasks(@Param("batchSize") int batchSize);

    /**
     * 原子性获取租约:仅当任务仍为 PENDING 或租约过期的 RUNNING 时更新。
     * 返回受影响行数(1 = 成功获取,0 = 已被其他实例抢占)。
     */
    @Modifying
    @Query(value = "UPDATE ai_task SET status = 'RUNNING', lease_owner = :owner, lease_expires_at = :leaseUntil, retry_count = retry_count + 1, updated_at = NOW() WHERE id = :id AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()))", nativeQuery = true)
    int acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);
}
