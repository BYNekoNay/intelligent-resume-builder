package com.intelligentresume.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证限流集成测试。
 *
 * <p>使用 {@link TestPropertySource} 将登录限流降至 2 次/分钟，
 * 独立于 {@link AuthControllerIT}（其使用 test profile 的 1000 次/分钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.rate-limit.login-per-minute=2",
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.refresh-per-minute=1000"
})
class AuthRateLimitIT {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/auth/login 超过每分钟 2 次返回 42901")
    void postLogin_rateLimited_returns42901() throws Exception {
        String body = """
                {"username":"nobody","password":"wrongpassword"}
                """;

        // 前 2 次正常（虽然密码错误返回 401，但不触发限流）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        // 第 3 次触发限流
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }
}
