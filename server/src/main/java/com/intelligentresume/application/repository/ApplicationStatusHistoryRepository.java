package com.intelligentresume.application.repository;

import com.intelligentresume.application.domain.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByApplicationRecordIdOrderByChangedAtAscIdAsc(Long applicationRecordId);

    List<ApplicationStatusHistory> findByApplicationRecordIdInOrderByApplicationRecordIdAscChangedAtAscIdAsc(
            Collection<Long> applicationRecordIds);
}
