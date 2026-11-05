package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.Resume;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 简历主档仓储。所有查询都以 {@code userId} 为条件，未提供"按 id 直接查"的方法，
 * 避免上层忘掉归属校验。
 *
 * <p>要点：
 * <ul>
 *     <li>{@link #findByIdAndUserIdForUpdate} 是 {@code SELECT ... FOR UPDATE}，用于读改写
 *         {@code current_version_id} 的场景；普通读取用 {@link #findByIdAndUserId} 即可。</li>
 *     <li>实体上的 {@code @SQLRestriction("deleted_at IS NULL")} 已全局排除软删行；
 *         {@code findByUserIdAndJobDescriptionIdAndDeletedAtIsNull} 又显式写了一次，属于冗余但结果一致。</li>
 *     <li>列表按 {@code updatedAt} 倒序，最近改过的排最前。</li>
 * </ul>
 */
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resume r WHERE r.id = :id AND r.userId = :userId")
    Optional<Resume> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    List<Resume> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<Resume> findByUserIdAndJobDescriptionIdAndDeletedAtIsNull(Long userId, Long jobDescriptionId);
}
