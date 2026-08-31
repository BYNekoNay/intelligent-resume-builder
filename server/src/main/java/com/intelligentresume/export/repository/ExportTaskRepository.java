package com.intelligentresume.export.repository;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * 导出任务仓库。DDL 含 user_id,跨用户校验直接 findByIdAndUserId。
 */
public interface ExportTaskRepository extends JpaRepository<ExportTask, Long> {

    Optional<ExportTask> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExportTask e WHERE e.id = :id")
    Optional<ExportTask> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExportTask e WHERE e.status = com.intelligentresume.export.domain.ExportStatus.SUCCESS " +
            "AND e.expiresAt < :now ORDER BY e.id ASC")
    List<ExportTask> findExpiredSuccessfulForUpdate(@Param("now") LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExportTask e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<ExportTask> findByStatusForUpdate(@Param("status") ExportStatus status, Pageable pageable);

    List<ExportTask> findByStatus(ExportStatus status);

    @Query(value = "SELECT * FROM export_task WHERE status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()) ORDER BY id ASC LIMIT :batchSize FOR UPDATE", nativeQuery = true)
    List<ExportTask> claimableTasks(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "UPDATE export_task SET status = 'RUNNING', lease_owner = :owner, lease_expires_at = :leaseUntil, retry_count = retry_count + 1, updated_at = NOW() WHERE id = :id AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < NOW()))", nativeQuery = true)
    int acquireLease(@Param("id") Long id, @Param("owner") String owner,
                     @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExportTask e WHERE e.id = :id " +
            "AND e.status = com.intelligentresume.export.domain.ExportStatus.RUNNING AND e.leaseOwner = :owner")
    Optional<ExportTask> findRunningByIdAndOwnerForUpdate(@Param("id") Long id, @Param("owner") String owner);

    long countByStatus(ExportStatus status);

    @Query("SELECT MIN(e.createdAt) FROM ExportTask e WHERE e.status = com.intelligentresume.export.domain.ExportStatus.PENDING")
    java.time.LocalDateTime findOldestPendingCreatedAt();
}
