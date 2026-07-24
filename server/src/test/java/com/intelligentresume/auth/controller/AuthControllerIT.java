package com.intelligentresume.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T02 §9 中 7 个集成测试场景。
 * 限流测试由 {@link AuthRateLimitIT} 单独覆盖（避免高限流阈值干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String REGISTER_BODY =
            """
            {"username":"ituser","email":"ituser@example.com","password":"correcthorse"}
            """;

    private static final String LOGIN_BODY =
            """
            {"username":"ituser","password":"correcthorse"}
            """;

    // ---- 注册 ----

    @Test
    @Order(1)
    @DisplayName("POST /api/auth/register 成功")
    void postRegister_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").isNumber())
                .andReturn();

        // refreshToken 被 @JsonIgnore，不出现在 JSON body 中
        String json = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("data").get("refreshToken") == null
                        || node.get("data").get("refreshToken").isNull(),
                "refreshToken 不应出现在 JSON body 中");

        // Set-Cookie 应包含 refresh token
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "注册应设置 refresh cookie");
        assertTrue(setCookie.contains("irt_refresh="), "cookie 名应为 irt_refresh");
    }

    // ---- 登录 ----

    @Test
    @Order(2)
    @DisplayName("POST /api/auth/login 成功并 Set-Cookie 含 HttpOnly + SameSite=Lax")
    void postLogin_setsCookieAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "登录应设置 refresh cookie");
        assertTrue(setCookie.contains("HttpOnly"), "cookie 应含 HttpOnly");
        assertTrue(setCookie.contains("SameSite=Lax"), "cookie 应含 SameSite=Lax");
        assertTrue(setCookie.contains("irt_refresh="), "cookie 名应为 irt_refresh");
    }

    // ---- 刷新 ----

    @Test
    @Order(3)
    @DisplayName("POST /api/auth/refresh 成功且返回新 Set-Cookie")
    void postRefresh_rotatesCookie() throws Exception {
        // 先登录获取 refresh cookie
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("irt_refresh");
        assertNotNull(refreshCookie, "登录后应有 refresh cookie");
        String oldToken = refreshCookie.getValue();

        // 用 cookie 刷新
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        Cookie newCookie = refreshResult.getResponse().getCookie("irt_refresh");
        assertNotNull(newCookie, "刷新后应有新 refresh cookie");
        assertNotEquals(oldToken, newCookie.getValue(), "刷新后 cookie 值应改变");

        // 旧 token 复用应返回 401
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("irt_refresh", oldToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ---- 退出 ----

    @Test
    @Order(4)
    @DisplayName("POST /api/auth/logout 成功")
    void postLogout_revokes() throws Exception {
        // 先登录
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("irt_refresh");
        assertNotNull(refreshCookie);

        // 退出
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 退出后旧 token 不可用
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ---- 当前用户 ----

    @Test
    @Order(5)
    @DisplayName("GET /api/auth/me 未登录返回 40101")
    void getMe_unauthenticated_returns40101() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/auth/me 已登录返回 UserInfo")
    void getMe_authenticated_returnsUserInfo() throws Exception {
        // 先登录获取 access token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        String json = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(json)
                .get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("ituser"))
                .andExpect(jsonPath("$.data.email").value("ituser@example.com"));
    }

    // ---- 注册校验 ----

    @Test
    @Order(7)
    @DisplayName("POST /api/auth/register 密码过短返回 40001")
    void postRegister_shortPassword_returns40001() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","email":"bob@example.com","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
