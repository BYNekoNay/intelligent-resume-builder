package com.intelligentresume.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 认证并发一致性集成测试（ideation #45 / #46）。
 *
 * <p>两用例都用「两线程 + CountDownLatch 同步起跑」制造真实并发，断言不依赖具体时序：
 * 无论哪方先到，结果都必须是「恰好一个赢家」。
 * - #46：并发注册同名用户 → 恰好一个 201、另一个稳定 409（修复前：应用层 exists 检查
 *   双双通过后撞唯一键 → 500）。
 * - #45：并发刷新同一 refresh token → 恰好一个签发者、另一个走复用检测 401，
 *   且族内未撤销会话不超过 1 条（修复前：两道刷新都成功 → 两个后继 token 同时有效）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthConcurrencyIT {

    @Autowired private AuthService authService;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenService tokenService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String PASSWORD = "correcthorse";

    @Test
    @DisplayName("并发注册同名用户: 恰好一个 201,另一个稳定 409（不再 500）")
    void concurrentRegistration_exactlyOneWinner() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "race_" + suffix;
        String body = "{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"%s\"}"
                .formatted(username, username, PASSWORD);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> registration = () -> {
            ready.countDown();
            go.await();
            MvcResult result = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            return result.getResponse().getStatus();
        };
        Future<Integer> first = pool.submit(registration);
        Future<Integer> second = pool.submit(registration);
        ready.await();
        go.countDown();
        List<Integer> statuses = List.of(
                first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, statuses.stream().filter(status -> status == 201).count(),
                "必须恰好一个注册成功: " + statuses);
        assertEquals(1, statuses.stream().filter(status -> status == 409).count(),
                "另一个必须是稳定 409 冲突（修复前可能为 500）: " + statuses);
    }

    @Test
    @DisplayName("并发刷新同一 refresh token: 恰好一个签发者,另一个复用检测 401,族内无双活会话")
    void concurrentRefresh_exactlyOneRotationWinner() throws Exception {
        User user = createUniqueUser();
        TokenResponse login = authService.login(new LoginRequest(user.getUsername(), PASSWORD));
        String refreshToken = login.refreshToken();
        String familyId = authSessionRepository
                .findByRefreshTokenHash(tokenService.hashToken(refreshToken))
                .orElseThrow(() -> new AssertionError("登录会话应存在"))
                .getTokenFamilyId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> refresh = () -> {
            ready.countDown();
            go.await();
            try {
                return authService.refresh(refreshToken, "IT", "127.0.0.1");
            } catch (BusinessException ex) {
                return ex;
            }
        };
        Future<Object> first = pool.submit(refresh);
        Future<Object> second = pool.submit(refresh);
        ready.await();
        go.countDown();
        List<Object> results = List.of(
                first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        long successes = results.stream().filter(TokenResponse.class::isInstance).count();
        long rejected = results.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(ex -> ex.getErrorCode() == ErrorCode.UNAUTHENTICATED)
                .count();
        assertEquals(1, successes, "刷新必须恰好一个赢家（修复前两个都会成功）: " + describe(results));
        assertEquals(1, rejected, "败者必须是 401（复用检测）: " + describe(results));

        long activeCount = authSessionRepository.findByTokenFamilyId(familyId).stream()
                .filter(session -> session.getRevokedAt() == null)
                .count();
        assertTrue(activeCount <= 1, "族内未撤销会话不得超过 1 条（防双活）: " + activeCount);
    }

    private String describe(List<Object> results) {
        return results.stream()
                .map(result -> result instanceof TokenResponse ? "TokenResponse"
                        : result instanceof BusinessException ex ? ex.getErrorCode().name() : result.toString())
                .toList().toString();
    }

    private User createUniqueUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("race_" + suffix);
        user.setEmail("race_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName("race");
        user.setStatus(User.UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}