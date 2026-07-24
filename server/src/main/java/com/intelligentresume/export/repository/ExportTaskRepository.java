package com.intelligentresume.export.repository;

import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 导出任务仓库。DDL 含 user_id,跨用户校验直接 findByIdAndUserId。
 */
public interface ExportTaskRepository extends JpaRepository<ExportTask, Long> {

    Optional<ExportTask> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExportTask e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<ExportTask> findByStatusForUpdate(@Param("status") ExportStatus status, Pageable pageable);

    List<ExportTask> findByStatus(ExportStatus status);
}
