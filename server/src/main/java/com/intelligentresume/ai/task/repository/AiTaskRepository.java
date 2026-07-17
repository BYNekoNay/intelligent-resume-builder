package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTask, Long> {

    Optional<AiTask> findByIdAndUserId(Long id, Long userId);

    Optional<AiTask> findByUserIdAndTaskTypeAndIdempotencyKey(Long userId,
                                                              AiTask.TaskType taskType,
                                                              String idempotencyKey);

    /**
     * 抢占一批可执行任务:PENDING 直接取;RUNNING 过期租约可重新抢占。
     * worker 用这个列表逐个 setStatus + setLeaseOwner + setLeaseExpiresAt 后 save。
     */
    @Query("""
            select t from AiTask t
            where (t.status = com.intelligentresume.ai.task.domain.AiTask.TaskStatus.PENDING
                or (t.status = com.intelligentresume.ai.task.domain.AiTask.TaskStatus.RUNNING
                    and t.leaseExpiresAt is not null
                    and t.leaseExpiresAt < :now))
            order by t.createdAt asc
            """)
    List<AiTask> findClaimableBatch(@Param("now") LocalDateTime now,
                                    org.springframework.data.domain.Pageable pageable);
}
