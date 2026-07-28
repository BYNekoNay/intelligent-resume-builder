package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    Optional<InterviewSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id AND s.userId = :userId")
    Optional<InterviewSession> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
