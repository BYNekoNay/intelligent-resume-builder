package com.intelligentresume.jobdescription.repository;

import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.JobDescriptionReference;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 岗位描述仓储。
 *
 * <p>要点：
 * <ul>
 *   <li>所有方法都把 {@code user_id} 写进条件（含投影查询），跨用户一律查不到；</li>
 *   <li>软删由实体 {@code @SQLRestriction} 统一过滤，这里不再重复写条件；</li>
 *   <li>{@code findByIdAndUserId} 会带出 MEDIUMTEXT 原文与解析 JSON，
 *       只需要标题/公司的场景请用投影方法 {@code findReferenceByIdAndUserId}；</li>
 *   <li>列表按 updatedAt 单键倒序，同毫秒更新的行次序不保证稳定（当前列表不分页，
 *       若将来加分页需补 id 兜底）。</li>
 * </ul>
 */
public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    /**
     * 轻量引用投影。字段别名必须与 getter 名一致（id/title/companyName），
     * 改别名会让 Spring Data 的接口投影绑定失败。
     */
    interface ReferenceProjection {
        Long getId();
        String getTitle();
        String getCompanyName();
    }

    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    /** 只取 id/title/companyName，避免为了展示"关联的 JD"而读取 MEDIUMTEXT 正文。 */
    @Query("""
            SELECT jd.id AS id, jd.title AS title, jd.companyName AS companyName
            FROM JobDescription jd
            WHERE jd.id = :id AND jd.userId = :userId
            """)
    Optional<ReferenceProjection> findReferenceByIdAndUserId(@Param("id") Long id,
                                                             @Param("userId") Long userId);

    List<JobDescription> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
