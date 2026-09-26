package com.intelligentresume.auth.service;

import com.intelligentresume.auth.domain.AuthSession;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.auth.repository.AuthSessionRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * refresh 令牌撤销持久化集成测试（SpringBootTest + H2 + Flyway）。
 *
 * <p>回归背景:refresh() 重放分支曾在同一事务内"撤销整族后抛异常",
 * 异常导致事务回滚,撤销标记从未落库 —— 攻击者重放旧 token 后,
 * 同族最新会话依然可用。本测试族专门复现该缺陷:
 * 在外层 refresh 事务回滚后,用真实 repository 断言撤销已持久化
 * （修复前族内最新会话的 revokedAt 为 null,此处断言失败）。
 *
 * <p>注意:测试方法刻意不加 @Transactional —— 服务调用各自独立提交/回滚,
 * 测试内 repository 查询读取的是已提交数据,才能暴露"回滚吞撤销"问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRefreshRevocationIT {

    @Autowired private AuthService authService;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenService tokenService;

    private static final String PASSWORD = "correcthorse";

    // ---- 缺陷回归:重放触发族撤销,外层回滚后仍持久化 ----

    @Test
    @DisplayName("重放旧 refresh token:族撤销独立事务提交,同族最新会话必须被撤销(修复前为 null)")
    void refresh_replay_persistsFamilyRevocationAfterOuterRollback() {
        User user = createUniqueUser();
        // 构造 S1 → 旋转 → S2 → 旋转 → S3（同族三代）
        TokenResponse t1 = authService.login(new LoginRequest(user.getUsername(), PASSWORD));
        TokenResponse t2 = authService.refresh(t1.refreshToken(), "IT", "127.0.0.1");
        TokenResponse t3 = authService.refresh(t2.refreshToken(), "IT", "127.0.0.1");

        String t3Hash = tokenService.hashToken(t3.refreshToken());
        String familyId = authSessionRepository.findByRefreshTokenHash(t3Hash)
                .orElseThrow(() -> new AssertionError("S3 会话应存在"))
                .getTokenFamilyId();

        // 重放已被轮转掉的 S1 → 应触发族撤销并 401
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh(t1.refreshToken(), "IT", "127.0.0.1"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());

        // 关键断言:外层 refresh 事务已回滚,但族撤销必须持久化
        List<AuthSession> family = authSessionRepository.findByTokenFamilyId(familyId);
        assertEquals(3, family.size(), "族内应有 S1/S2/S3 三个会话");
        assertTrue(family.stream().allMatch(s -> s.getRevokedAt() != null),
                "族内全部会话都必须被撤销（修复前 S3 的 revokedAt 为 null）");

        AuthSession latest = family.stream()
                .filter(s -> t3Hash.equals(s.getRefreshTokenHash()))
                .findFirst().orElseThrow(() -> new AssertionError("S3 会话应在族内"));
        assertEquals("refresh_reuse_detected", latest.getRevokeReason(),
                "最新会话应因重放检测被撤销");
    }

    @Test
    @DisplayName("正常轮转:旧会话被撤销,新会话可继续使用")
    void refresh_rotation_oldRevoked_newUsable() {
        User user = createUniqueUser();
        TokenResponse t1 = authService.login(new LoginRequest(user.getUsername(), PASSWORD));

        TokenResponse t2 = authService.refresh(t1.refreshToken(), "IT", "127.0.0.1");

        AuthSession s1 = authSessionRepository
                .findByRefreshTokenHash(tokenService.hashToken(t1.refreshToken()))
                .orElseThrow(() -> new AssertionError("S1 会话应存在"));
        assertNotNull(s1.getRevokedAt(), "轮转后旧会话应被撤销");
        assertEquals("rotated", s1.getRevokeReason());

        // 新会话可继续正常刷新（证明轮转本身未被误伤）
        TokenResponse t3 = authService.refresh(t2.refreshToken(), "IT", "127.0.0.1");
        assertNotNull(t3.refreshToken());
        assertNotEquals(t2.refreshToken(), t3.refreshToken(), "每次轮转应签发新 refresh token");
    }

    // ---- 同类缺陷回归:过期标记被外层回滚吞掉 ----

    @Test
    @DisplayName("过期 refresh token:会话过期标记独立事务持久化(修复前因回滚为 null)")
    void refresh_expired_persistsSessionRevocationAfterOuterRollback() {
        User user = createUniqueUser();
        TokenResponse t1 = authService.login(new LoginRequest(user.getUsername(), PASSWORD));

        AuthSession s1 = authSessionRepository
                .findByRefreshTokenHash(tokenService.hashToken(t1.refreshToken()))
                .orElseThrow(() -> new AssertionError("S1 会话应存在"));
        // 人为令会话过期
        s1.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        authSessionRepository.save(s1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh(t1.refreshToken(), "IT", "127.0.0.1"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());

        AuthSession reloaded = authSessionRepository.findById(s1.getId()).orElseThrow();
        assertNotNull(reloaded.getRevokedAt(),
                "过期标记必须持久化（修复前因事务回滚而为 null,每次重试都重复标记）");
        assertEquals("expired", reloaded.getRevokeReason());
    }

    // ---- 辅助方法 ----

    private User createUniqueUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("revit_" + suffix);
        user.setEmail("revit_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("revit");
        user.setStatus(User.UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
