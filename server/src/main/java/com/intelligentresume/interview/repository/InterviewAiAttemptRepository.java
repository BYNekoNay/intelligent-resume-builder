package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InterviewAiAttemptRepository extends JpaRepository<InterviewAiAttempt, Long> {
    Optional<InterviewAiAttempt> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<InterviewAiAttempt> findBySessionIdAndOperationTypeAndRoundNo(
            Long sessionId, AiAttemptOperationType operationType, Integer roundNo);
    List<InterviewAiAttempt> findAllBySessionId(Long sessionId);
    Optional<InterviewAiAttempt> findFirstBySessionIdAndStatusOrderByUpdatedAtDesc(
            Long sessionId, AiAttemptStatus status);
    Optional<InterviewAiAttempt> findTopBySessionIdAndStatusOrderByIdDesc(
            Long sessionId, AiAttemptStatus status);

    @Query("""
            select coalesce(sum(a.attemptCount), 0)
            from InterviewAiAttempt a
            where a.userId = :userId and a.createdAt >= :after
            """)
    long sumAttemptCountByUserIdAndCreatedAtAfter(@Param("userId") Long userId,
                                                   @Param("after") LocalDateTime after);
}
