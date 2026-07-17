package com.intelligentresume.export.repository;

import com.intelligentresume.export.domain.ExportTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExportTaskRepository extends JpaRepository<ExportTask, Long> {

    Optional<ExportTask> findByIdAndUserId(Long id, Long userId);

    List<ExportTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ExportTask> findTop10ByStatusOrderByCreatedAtAsc(ExportTask.ExportStatus status);
}
