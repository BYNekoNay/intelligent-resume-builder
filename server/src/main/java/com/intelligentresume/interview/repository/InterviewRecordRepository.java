package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewRecordRepository extends JpaRepository<InterviewRecord, Long> {
    List<InterviewRecord> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
    List<InterviewRecord> findBySessionIdInOrderByCreatedAtAsc(Collection<Long> sessionIds);
    long countBySessionId(Long sessionId);
    @Query("SELECT r FROM InterviewRecord r, InterviewSession s WHERE r.id = :id AND r.sessionId = s.id AND s.userId = :userId")
    Optional<InterviewRecord> findOwned(@Param("id") Long id, @Param("userId") Long userId);
}
