package com.intelligentresume.ats.repository;

import com.intelligentresume.ats.domain.AtsCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * {@code ats_check_result} 表的数据库访问入口。
 *
 * <p>一句话定位：查检查记录、按幂等键查重、加行锁改状态，三个需求各对应一个方法；
 * 被 {@code AtsService}（读）和 {@code AtsResultStateService}（写）使用。
 *
 * <p>要点：
 * <ul>
 *   <li>所有查询都带 {@code userId}：这是越权隔离的唯一手段，不要新增只按 id 查的方法。</li>
 *   <li>{@link #findByIdAndUserId} 是普通读，用于展示；{@link #findOwnedForUpdate} 是
 *       {@code PESSIMISTIC_WRITE}（行锁），供 {@code AtsResultStateService} 做
 *       "读 result_json → 改 → 写回" 的串行化。要写 result_json 就必须用它。</li>
 *   <li>{@link #findByUserIdAndIdempotencyKey} 依赖唯一索引
 *       {@code uq_ats_check_result_user_idempotency}（V22）；没有索引时并发重试会产生两条记录。</li>
 * </ul>
 */
public interface AtsCheckResultRepository extends JpaRepository<AtsCheckResult, Long> {
    Optional<AtsCheckResult> findByIdAndUserId(Long id, Long userId);
    Optional<AtsCheckResult> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AtsCheckResult r where r.id = :id and r.userId = :userId")
    Optional<AtsCheckResult> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
