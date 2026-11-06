package com.intelligentresume.auth.repository;

import com.intelligentresume.auth.domain.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 刷新会话表的数据访问。
 *
 * <p>【小白导览】读写 {@code auth_session} 表（一次登录一行，管 refresh token 的续期与吊销）。
 * 改坏它 → 刷新流程找不到会话，所有用户被踢回登录页；撤销查询写错 → 被吊销的令牌仍可用。
 *
 * <p>三条查询口径不同，别混用：
 * <ul>
 *   <li>{@code findByRefreshTokenHash} 是刷新入口，按摘要精确定位一行；</li>
 *   <li>{@code findByTokenFamilyId} 用于重放检测后的整族撤销，包含已撤销的行；</li>
 *   <li>{@code findByUserIdAndRevokedAtIsNull} 只取仍有效的会话，供 logout-all / 改密后全撤。</li>
 * </ul>
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    /** 按 refresh token 摘要查会话（原文不落库，只能按摘要查）。 */
    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * 原子撤销（CAS）：仅当会话仍未被撤销时置为撤销，返回受影响行数。
     * refresh 轮换的一次性边界——并发刷新同一 token 时只有一个请求拿到 1，
     * 其余拿到 0 并走复用检测，不会出现两个后继 token 同时有效；
     * 相比行锁不持有跨语句锁，故与 REQUIRES_NEW 撤销服务（过期/复用分支）组合无自锁风险。
     */
    @Modifying
    @Query("UPDATE AuthSession session SET session.revokedAt = :now, session.revokeReason = :reason "
            + "WHERE session.id = :id AND session.revokedAt IS NULL")
    int revokeIfActive(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** 取整族会话（含已撤销行）：重放检测要撤销的是「整条链」，不是当前这一行。 */
    List<AuthSession> findByTokenFamilyId(String tokenFamilyId);

    /** 该用户全部仍有效的会话；改密/改邮箱/删号后据此批量撤销。 */
    List<AuthSession> findByUserIdAndRevokedAtIsNull(Long userId);
}