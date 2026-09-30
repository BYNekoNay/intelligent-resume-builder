package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * 最近一轮记录（#43 读放大）：状态接口只需要**最后一轮**的评估，而它是前端
     * 1/2/4/5s 轮询的热路径（每轮面试期间可调用数十次）。此前用
     * {@link #findBySessionIdOrderByRoundNoAscIdAsc} 全量读出本会话所有轮次再取末条，
     * 单次轮询的读取量随已完成轮数线性增长（实测：9 轮会话 19 行/次、1 轮会话 3 行/次），
     * 且这些行携带 {@code answer_text}(MEDIUMTEXT) 与 {@code feedback_json}(JSON)。
     * 取末条的次序与 {@code round_no ASC, id ASC} 的末条一致（双键降序 LIMIT 1）。
     */
    Optional<InterviewRecord> findFirstBySessionIdOrderByRoundNoDescIdAsc(Long sessionId);

    /** 账号数据导出（#7）：按会话批量取全部轮次记录。 */
    List<InterviewRecord> findBySessionIdInOrderBySessionIdAscRoundNoAscIdAsc(Collection<Long> sessionIds);

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

    /**
     * 锁定面试记录行（#24）：面试资产「先查后插」的幂等创建需要串行化同一记录的
     * 并发请求；锁随创建事务释放，唯一索引 {@code uq_interview_asset_user_record}
     * 作为兜底。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InterviewRecord r, InterviewSession s WHERE r.id = :id AND r.sessionId = s.id AND s.userId = :userId")
    Optional<InterviewRecord> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
