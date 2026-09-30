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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
        "app.security.rate-limit.refresh-per-minute=1000",
        "app.security.rate-limit.change-credential-per-minute=2"
})
class AuthRateLimitIT {

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /**
     * 强制原始（未解码）requestURI：MockMvc 的 URL 参数会把 {@code %} 二次编码
     * （{@code %6C} → {@code %256C}），与真实 Tomcat 收到的请求不符；此处与真实
     * HTTP 探针保持同一形态（实测 Tomcat 下 {@code /api/auth/%6Cogin} 会解码后路由到 login）。
     */
    private static RequestPostProcessor rawUri(String rawPath) {
        return request -> {
            request.setRequestURI(rawPath);
            return request;
        };
    }

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

    @Test
    @DisplayName("百分号转义路径（/api/auth/%6Cogin）不能绕过登录限流")
    void encodedLoginPath_cannotBypassRateLimit() throws Exception {
        String body = """
                {"username":"nobody","password":"wrongpassword"}
                """;
        // 独立 IP：避免与其它用例共享同一分桶（桶为进程内存、跨用例存活）
        String ip = "198.51.100.7";

        // 明文用掉 2 次配额（401 不计入响应码断言之外，但请求已被计数）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login").with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        // 明文第 3 次被限流（窗口基线）
        mockMvc.perform(post("/api/auth/login").with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());

        // 转义路径等价于同一端点（MVC 解码后路由到 login 控制器）：
        // 修复前未命中限流 path 精确匹配 → 直达控制器并返回 401，构成限流绕过（真实 HTTP 已实证）
        mockMvc.perform(post("/api/auth/login").with(rawUri("/api/auth/%6Cogin")).with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    @DisplayName("凭证变更端点受限且共享分桶（改密/改邮箱交替不能获得双倍预算）")
    void credentialChangeEndpoints_rateLimited() throws Exception {
        // 独立 IP：凭证桶 key 为 group|ip，与其它用例路径不同，但保持隔离更稳健
        String ip = "198.51.100.8";
        String passwordBody = """
                {"currentPassword":"guess-guess-guess","newPassword":"new-password-123"}
                """;

        // 未登录请求也会先经限流器计数，再因未认证返回 401（防口令爆破的第一道闸）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/me/password").with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(passwordBody))
                    .andExpect(status().isUnauthorized());
        }

        // 第 3 次改邮箱与改密共享分桶 → 429（修复前两个端点均不在限流映射内，真实 HTTP 实测无限 401）
        mockMvc.perform(post("/api/auth/me/email").with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"takeover@example.test","currentPassword":"guess-guess-guess"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }
}
