package com.intelligentresume.auth.repository;

import com.intelligentresume.auth.domain.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

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

    List<AuthSession> findByTokenFamilyId(String tokenFamilyId);

    List<AuthSession> findByUserIdAndRevokedAtIsNull(Long userId);
}