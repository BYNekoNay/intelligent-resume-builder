package com.intelligentresume.application.repository;

import com.intelligentresume.application.domain.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, Long> {
    List<ApplicationRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<ApplicationRecord> findByIdAndUserId(Long id, Long userId);
}
