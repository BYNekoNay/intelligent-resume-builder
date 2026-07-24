package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.AuthSession;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.auth.repository.AuthSessionRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 单元测试（Mockito）。
 *
 * <p>覆盖 T02 §9 中 10 个必测场景：注册(4)、登录(2)、刷新轮换(2)、退出(2)。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private TokenService tokenService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, authSessionRepository, tokenService, passwordEncoder);
    }

    // ---- 注册 ----

    @Test
    @DisplayName("正常路径: 注册成功")
    void register_success() {
        RegisterRequest req = new RegisterRequest("alice", "alice@example.com", "correcthorse");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("correcthorse")).thenReturn("$2a$10$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        stubTokenServiceForNewFamily();

        TokenResponse resp = authService.register(req);

        assertNotNull(resp.accessToken());
        assertEquals("access-token", resp.accessToken());
        assertEquals(3600, resp.accessTokenExpiresInSeconds());
        assertEquals("refresh-raw", resp.refreshToken());

        // 验证 session 被保存
        ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository).save(sessionCaptor.capture());
        AuthSession saved = sessionCaptor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals("family-uuid", saved.getTokenFamilyId());
        assertEquals("refresh-hash", saved.getRefreshTokenHash());
        assertNull(saved.getRevokedAt());
    }

    @Test
    @DisplayName("失败路径: 重复用户名注册抛出 BusinessException")
    void register_duplicateUsername_throws() {
        RegisterRequest req = new RegisterRequest("alice", "alice@example.com", "correcthorse");
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(req));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("失败路径: 重复邮箱注册抛出 BusinessException")
    void register_duplicateEmail_throws() {
        RegisterRequest req = new RegisterRequest("alice", "alice@example.com", "correcthorse");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(req));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("边界路径: 密码长度 < 8 校验失败")
    void register_shortPassword_validationFails() {
        RegisterRequest req = new RegisterRequest("alice", "alice@example.com", "short");
        Set<ConstraintViolation<RegisterRequest>> violations = VALIDATOR.validate(req);
        assertFalse(violations.isEmpty(), "密码长度 < 8 应触发校验失败");
    }

    // ---- 登录 ----

    @Test
    @DisplayName("正常路径: 登录成功,返回 access 与 refresh token")
    void login_success() {
        LoginRequest req = new LoginRequest("alice", "correcthorse");
        User user = activeUser(1L, "alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correcthorse", "$2a$10$hashed")).thenReturn(true);
        stubTokenServiceForNewFamily();

        TokenResponse resp = authService.login(req);

        assertEquals("access-token", resp.accessToken());
        assertEquals("refresh-raw", resp.refreshToken());
        verify(authSessionRepository).save(any(AuthSession.class));
    }

    @Test
    @DisplayName("失败路径: 错误密码返回 40101")
    void login_wrongPassword_throws() {
        LoginRequest req = new LoginRequest("alice", "wrongpassword");
        User user = activeUser(1L, "alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "$2a$10$hashed")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(req));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    // ---- 刷新轮换 ----

    @Test
    @DisplayName("正常路径: 刷新令牌轮换,旧令牌被撤销")
    void refresh_rotatesOldSession() {
        AuthSession oldSession = activeSession(10L, 1L, "family-1", "old-hash");
        when(tokenService.hashToken("old-refresh")).thenReturn("old-hash");
        when(authSessionRepository.findByRefreshTokenHash("old-hash"))
                .thenReturn(Optional.of(oldSession));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L, "alice")));
        when(tokenService.issueRefreshToken()).thenReturn("new-refresh");
        when(tokenService.hashToken("new-refresh")).thenReturn("new-hash");
        when(tokenService.getRefreshTokenTtlSeconds()).thenReturn(2592000L);
        when(tokenService.issueAccessToken(1L, "alice")).thenReturn("new-access");
        when(tokenService.getAccessTokenTtlSeconds()).thenReturn(3600L);

        TokenResponse resp = authService.refresh("old-refresh", "TestAgent", "127.0.0.1");

        // 旧 session 被撤销
        assertNotNull(oldSession.getRevokedAt());
        assertEquals("rotated", oldSession.getRevokeReason());

        // 新 session 被创建
        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository, times(2)).save(captor.capture());
        AuthSession fresh = captor.getAllValues().get(1);
        assertEquals("family-1", fresh.getTokenFamilyId());
        assertEquals("new-hash", fresh.getRefreshTokenHash());
        assertNull(fresh.getRevokedAt());

        assertEquals("new-access", resp.accessToken());
        assertEquals("new-refresh", resp.refreshToken());
    }

    @Test
    @DisplayName("失败路径: 旧 refresh token 复用撤销整个 token family")
    void refresh_reuseRevokesFamily() {
        // 已撤销的 session（旧 token 被复用）
        AuthSession revokedSession = activeSession(10L, 1L, "family-1", "old-hash");
        revokedSession.setRevokedAt(LocalDateTime.now().minusMinutes(5));
        revokedSession.setRevokeReason("rotated");

        AuthSession activeSession = activeSession(11L, 1L, "family-1", "current-hash");

        when(tokenService.hashToken("stolen-token")).thenReturn("old-hash");
        when(authSessionRepository.findByRefreshTokenHash("old-hash"))
                .thenReturn(Optional.of(revokedSession));
        when(authSessionRepository.findByTokenFamilyId("family-1"))
                .thenReturn(List.of(revokedSession, activeSession));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh("stolen-token", "TestAgent", "127.0.0.1"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());

        // 整族被撤销
        assertNotNull(revokedSession.getRevokedAt());
        assertNotNull(activeSession.getRevokedAt());
        assertEquals("refresh_reuse_detected", activeSession.getRevokeReason());
        verify(authSessionRepository).saveAll(anyList());
    }

    // ---- 退出 ----

    @Test
    @DisplayName("正常路径: 退出登录撤销当前 session")
    void logout_revokesCurrent() {
        AuthSession session = activeSession(10L, 1L, "family-1", "hash-1");
        when(tokenService.hashToken("my-refresh")).thenReturn("hash-1");
        when(authSessionRepository.findByRefreshTokenHash("hash-1"))
                .thenReturn(Optional.of(session));

        authService.logout("my-refresh");

        assertNotNull(session.getRevokedAt());
        assertEquals("logout", session.getRevokeReason());
        verify(authSessionRepository).save(session);
    }

    @Test
    @DisplayName("正常路径: allSessions=true 撤销用户全部 session")
    void logout_allSessions_revokesAll() {
        AuthSession s1 = activeSession(10L, 1L, "family-1", "hash-1");
        AuthSession s2 = activeSession(11L, 1L, "family-2", "hash-2");
        when(authSessionRepository.findByUserIdAndRevokedAtIsNull(1L))
                .thenReturn(List.of(s1, s2));

        authService.logoutAll(1L);

        assertNotNull(s1.getRevokedAt());
        assertNotNull(s2.getRevokedAt());
        assertEquals("logout_all", s1.getRevokeReason());
        assertEquals("logout_all", s2.getRevokeReason());
        verify(authSessionRepository).saveAll(List.of(s1, s2));
    }

    // ---- 辅助方法 ----

    private void stubTokenServiceForNewFamily() {
        when(tokenService.newTokenFamilyId()).thenReturn("family-uuid");
        when(tokenService.issueRefreshToken()).thenReturn("refresh-raw");
        when(tokenService.hashToken("refresh-raw")).thenReturn("refresh-hash");
        when(tokenService.issueAccessToken(eq(1L), eq("alice"))).thenReturn("access-token");
        when(tokenService.getAccessTokenTtlSeconds()).thenReturn(3600L);
        when(tokenService.getRefreshTokenTtlSeconds()).thenReturn(2592000L);
    }

    private User activeUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("$2a$10$hashed");
        user.setDisplayName(username);
        user.setStatus(User.UserStatus.ACTIVE);
        return user;
    }

    private AuthSession activeSession(Long id, Long userId, String familyId, String hash) {
        AuthSession session = new AuthSession();
        session.setId(id);
        session.setUserId(userId);
        session.setTokenFamilyId(familyId);
        session.setRefreshTokenHash(hash);
        session.setIssuedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusDays(30));
        return session;
    }
}
