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

/**
 * AI attempt 的读取入口。查询方法被幂等判定、配额统计、失败信息回显三处共用。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code findByUserIdAndIdempotencyKey} 是幂等入口，依赖 {@code (user_id, idempotency_key)} 唯一约束。</li>
 *   <li>{@code findBySessionIdAndOperationTypeAndRoundNo} 依赖另一条唯一约束，用于规则模式复用本轮 attempt。</li>
 *   <li>配额统计按 {@code sum(attemptCount)} 而非行数：重试与契约修复都体现在代数上。</li>
 *   <li>本接口没有加锁版本：状态推进的并发安全靠会话行锁 + 状态复核，而不是锁 attempt。</li>
 * </ul>
 */
public interface InterviewAiAttemptRepository extends JpaRepository<InterviewAiAttempt, Long> {
    Optional<InterviewAiAttempt> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<InterviewAiAttempt> findBySessionIdAndOperationTypeAndRoundNo(
            Long sessionId, AiAttemptOperationType operationType, Integer roundNo);
    List<InterviewAiAttempt> findAllBySessionId(Long sessionId);
    /**
     * 最新一条指定状态尝试，按 (updated_at, id) 双键降序。interview_ai_attempt 的
     * updated_at 是秒级 DATETIME，同一秒内不同 round/operation 可各有一条 FAILED；
     * 单键排序在并列时无稳定契约，必须由自增 id 兜底，否则 aiFailure 会取到较早的
     * 那条并返回误导性的 messageCode/retryable。
     */
    Optional<InterviewAiAttempt> findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc(
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
