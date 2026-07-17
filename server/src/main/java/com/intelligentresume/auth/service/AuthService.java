package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.AuthSession;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.dto.CurrentUserResponse;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.repository.AuthSessionRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 认证领域服务:注册 / 登录 / 刷新 / 退出。
 *
 * <p>关键约定:
 * <ul>
 *     <li>refresh token 原文不落库,仅保存 SHA-256 摘要。</li>
 *     <li>每个用户一次刷新会生成新的 token family;任何旧 token 复用都会撤销整族。</li>
 *     <li>密码使用 {@link PasswordEncoder}(BCrypt) 散列。</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       AuthSessionRepository authSessionRepository,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已被占用");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已被占用");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.username());
        userRepository.save(user);

        return issueNewFamily(user, "register");
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .or(() -> userRepository.findByEmail(request.username()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误"));

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "账号或密码错误");
        }

        return issueNewFamily(user, "login");
    }

    /**
     * 用 refresh token 旋转出新的 access + refresh。
     * 任一旧 token 复用都会撤销整族并要求重新登录。
     */
    @Transactional
    public TokenResponse refresh(String presentedRefreshToken, String userAgent, String ip) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "缺少刷新令牌");
        }
        String presentedHash = tokenService.hashToken(presentedRefreshToken);

        AuthSession session = authSessionRepository.findByRefreshTokenHash(presentedHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌不存在"));

        // 旧令牌被复用 → 撤销整族
        if (session.getRevokedAt() != null) {
            revokeFamily(session.getTokenFamilyId(), "refresh_reuse_detected");
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌已失效,请重新登录");
        }

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason("expired");
            authSessionRepository.save(session);
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌已过期");
        }

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // 撤销旧会话、签发新会话
        session.setRevokedAt(LocalDateTime.now());
        session.setRevokeReason("rotated");
        authSessionRepository.save(session);

        AuthSession next = new AuthSession();
        next.setUserId(user.getId());
        next.setTokenFamilyId(session.getTokenFamilyId());
        next.setRefreshTokenHash(tokenService.hashToken(presentedRefreshToken));
        // 注意:issued/expires 由调用方刷新原文 token 后再赋,见 issueNewFamily

        // 直接发新一对(保持原 family)
        String newRefresh = tokenService.issueRefreshToken();
        AuthSession fresh = new AuthSession();
        fresh.setUserId(user.getId());
        fresh.setTokenFamilyId(session.getTokenFamilyId());
        fresh.setRefreshTokenHash(tokenService.hashToken(newRefresh));
        fresh.setIssuedAt(LocalDateTime.now());
        fresh.setExpiresAt(LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds()));
        fresh.setUserAgent(userAgent);
        fresh.setIpAddress(ip);
        authSessionRepository.save(fresh);

        return tokensFor(user, newRefresh);
    }

    @Transactional
    public void logout(String presentedRefreshToken) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            return;
        }
        String hash = tokenService.hashToken(presentedRefreshToken);
        Optional<AuthSession> maybe = authSessionRepository.findByRefreshTokenHash(hash);
        maybe.ifPresent(session -> {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason("logout");
            authSessionRepository.save(session);
        });
    }

    @Transactional
    public void logoutAll(Long userId) {
        List<AuthSession> active = authSessionRepository.findByUserIdAndRevokedAtIsNull(userId);
        LocalDateTime now = LocalDateTime.now();
        for (AuthSession session : active) {
            session.setRevokedAt(now);
            session.setRevokeReason("logout_all");
        }
        authSessionRepository.saveAll(active);
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        user.setStatus(User.UserStatus.DISABLED);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
        logoutAll(userId);
    }

    public CurrentUserResponse currentUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName());
    }

    // ----------------------------------------------------------------
    // private helpers
    // ----------------------------------------------------------------

    private TokenResponse issueNewFamily(User user, String reason) {
        String familyId = tokenService.newTokenFamilyId();
        String refresh = tokenService.issueRefreshToken();

        AuthSession session = new AuthSession();
        session.setUserId(user.getId());
        session.setTokenFamilyId(familyId);
        session.setRefreshTokenHash(tokenService.hashToken(refresh));
        session.setIssuedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds()));
        session.setUserAgent(reason);
        authSessionRepository.save(session);

        return tokensFor(user, refresh);
    }

    private TokenResponse tokensFor(User user, String refresh) {
        String access = tokenService.issueAccessToken(user.getId(), user.getUsername());
        return new TokenResponse(access, tokenService.getAccessTokenTtlSeconds(), refresh);
    }

    private void revokeFamily(String familyId, String reason) {
        List<AuthSession> family = authSessionRepository.findByTokenFamilyId(familyId);
        LocalDateTime now = LocalDateTime.now();
        for (AuthSession session : family) {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(now);
                session.setRevokeReason(reason);
            }
        }
        authSessionRepository.saveAll(family);
    }

    /** 提供无参重载,避免 controller 在没有 userId 时绕过 SecurityContext。 */
    public CurrentUserResponse currentUser() {
        throw new BusinessException(ErrorCode.INTERNAL,
                "AuthService.currentUser() 必须由 Controller 显式传入 userId");
    }
}
