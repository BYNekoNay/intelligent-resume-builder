package com.intelligentresume.application.repository;

import com.intelligentresume.application.domain.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, Long> {
    List<ApplicationRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ApplicationRecord> findByIdAndUserId(Long id, Long userId);

    /**
     * 按跟进筛选查询当前用户投递记录。
     *
     * <p>followUp 取值：ALL（不过滤）/ TODAY（next_follow_up_at 位于 [startOfDay, endOfDay)）/
     * OVERDUE（已过且状态非终态 OFFERED/REJECTED/WITHDRAWN）。服务端按服务器本地日界计算。
     */
    @Query("""
            SELECT a FROM ApplicationRecord a
            WHERE a.userId = :userId
              AND (:followUp = 'ALL'
                   OR (:followUp = 'TODAY' AND a.nextFollowUpAt IS NOT NULL
                       AND a.nextFollowUpAt >= :startOfDay AND a.nextFollowUpAt < :endOfDay)
                   OR (:followUp = 'OVERDUE' AND a.nextFollowUpAt IS NOT NULL AND a.nextFollowUpAt < :now
                       AND a.status <> com.intelligentresume.application.domain.ApplicationStatus.OFFERED
                       AND a.status <> com.intelligentresume.application.domain.ApplicationStatus.REJECTED
                       AND a.status <> com.intelligentresume.application.domain.ApplicationStatus.WITHDRAWN))
            ORDER BY a.updatedAt DESC
            """)
    List<ApplicationRecord> findByUserIdAndFollowUp(@Param("userId") Long userId,
                                                    @Param("followUp") String followUp,
                                                    @Param("startOfDay") LocalDateTime startOfDay,
                                                    @Param("endOfDay") LocalDateTime endOfDay,
                                                    @Param("now") LocalDateTime now);
}
