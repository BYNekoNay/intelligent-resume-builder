package com.intelligentresume.auth.repository;

import com.intelligentresume.auth.domain.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    List<AuthSession> findByTokenFamilyId(String tokenFamilyId);

    List<AuthSession> findByUserIdAndRevokedAtIsNull(Long userId);
}