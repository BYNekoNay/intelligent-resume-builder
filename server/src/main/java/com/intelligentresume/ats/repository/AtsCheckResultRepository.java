package com.intelligentresume.ats.repository;

import com.intelligentresume.ats.domain.AtsCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AtsCheckResultRepository extends JpaRepository<AtsCheckResult, Long> {
    Optional<AtsCheckResult> findByIdAndUserId(Long id, Long userId);
    Optional<AtsCheckResult> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AtsCheckResult r where r.id = :id and r.userId = :userId")
    Optional<AtsCheckResult> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
