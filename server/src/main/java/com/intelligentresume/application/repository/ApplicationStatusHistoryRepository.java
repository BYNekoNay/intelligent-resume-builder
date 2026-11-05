package com.intelligentresume.application.repository;

import com.intelligentresume.application.domain.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 投递状态历史仓储：只追加与查询，不提供更新语义。
 *
 * <p>两个查询都按 {@code changedAt, id} 升序——同一秒内的多条迁移靠自增 id 保证稳定顺序。
 */
public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByApplicationRecordIdOrderByChangedAtAscIdAsc(Long applicationRecordId);

    List<ApplicationStatusHistory> findByApplicationRecordIdInOrderByApplicationRecordIdAscChangedAtAscIdAsc(
            Collection<Long> applicationRecordIds);
}
