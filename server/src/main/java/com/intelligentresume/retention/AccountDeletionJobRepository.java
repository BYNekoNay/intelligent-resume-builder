package com.intelligentresume.retention;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionJobRepository extends JpaRepository<AccountDeletionJob, Long> {

    /** 该用户当前未完成的删号任务（撤销与幂等判断都用它；一用户最多一行 PENDING）。 */
    Optional<AccountDeletionJob> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, AccountDeletionJob.Status status);

    /** 撤销删号：把该用户全部 PENDING 行翻 CANCELLED（正常恰好一行）。 */
    List<AccountDeletionJob> findByUserIdAndStatus(Long userId, AccountDeletionJob.Status status);

    /** 清扫候选：窗口已过且未完成的任务（含 PARTIAL_FAILED 重入 —— DELETE 幂等，重跑安全）。 */
    List<AccountDeletionJob> findByStatusInAndCancelUntilLessThanEqualOrderByIdAsc(
            List<AccountDeletionJob.Status> statuses, LocalDateTime cutoff, Pageable pageable);
}
