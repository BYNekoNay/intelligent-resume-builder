package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewRecordRepository extends JpaRepository<InterviewRecord, Long> {
    /**
     * 按轮次升序返回（#75）：轮次是面试的领域顺序，且 V18 的
     * {@code uq_interview_record_session_round(session_id, round_no)} 保证其唯一；
     * 以 createdAt 为主序在同毫秒写入时不稳定。{@code id} 仅作防御性次序键。
     */
    List<InterviewRecord> findBySessionIdOrderByRoundNoAscIdAsc(Long sessionId);

    interface ScoreProjection {
        Long getSessionId();
        Integer getRoundScore();
    }

    @Query("""
            SELECT r.sessionId AS sessionId, r.roundScore AS roundScore
            FROM InterviewRecord r
            WHERE r.sessionId IN :sessionIds
            ORDER BY r.roundNo ASC, r.id ASC
            """)
    List<ScoreProjection> findScoresBySessionIdInOrderByRoundNoAscIdAsc(
            @Param("sessionIds") Collection<Long> sessionIds);

    long countBySessionId(Long sessionId);
    @Query("SELECT r FROM InterviewRecord r, InterviewSession s WHERE r.id = :id AND r.sessionId = s.id AND s.userId = :userId")
    Optional<InterviewRecord> findOwned(@Param("id") Long id, @Param("userId") Long userId);
}
