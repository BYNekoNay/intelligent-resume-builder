package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    Optional<InterviewSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id AND s.userId = :userId")
    Optional<InterviewSession> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 历史会话列表：仅 COMPLETED，支持按 JD 筛选，按 updatedAt 降序。
     */
    @Query("""
            SELECT s FROM InterviewSession s
            WHERE s.userId = :userId
              AND s.status = :status
              AND (:jobDescriptionId IS NULL OR s.jobDescriptionId = :jobDescriptionId)
            ORDER BY s.updatedAt DESC, s.id DESC
            """)
    List<InterviewSession> findCompletedByUserId(@Param("userId") Long userId,
                                                 @Param("status") InterviewStatus status,
                                                 @Param("jobDescriptionId") Long jobDescriptionId);
}
