package com.intelligentresume.jobdescription.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JD 控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T05 §9 中 4 个集成测试场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobDescriptionControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String token;
    private static Long jobId;

    // ---- 辅助方法 ----

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("data").get("accessToken").asText();
    }

    // ---- 注册用户 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户")
    void registerUser() throws Exception {
        token = registerAndGetToken("jd_user", "jd_user@example.com", "correcthorse");
        assertNotNull(token);
    }

    // ---- 1. 创建 ----

    @Test
    @Order(2)
    @DisplayName("POST /api/jobs 201")
    void postCreate_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java后端工程师",
                                  "companyName": "某科技公司",
                                  "jdText": "负责 Spring Boot 微服务开发,熟悉 MySQL/Redis,3 年以上经验,本科及以上学历。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.title").value("Java后端工程师"))
                .andExpect(jsonPath("$.data.companyName").value("某科技公司"))
                .andReturn();

        jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    // ---- 2. 列表 ----

    @Test
    @Order(3)
    @DisplayName("GET /api/jobs 返回本人列表")
    void getList_returnsOwn() throws Exception {
        mockMvc.perform(get("/api/jobs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("Java后端工程师"));
    }

    // ---- 3. 解析 ----

    @Test
    @Order(4)
    @DisplayName("POST /api/jobs/{id}/parse 200 + 返回解析结果")
    void postParse_200() throws Exception {
        mockMvc.perform(post("/api/jobs/" + jobId + "/parse")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.parsedKeywordsJson").isNotEmpty())
                .andExpect(jsonPath("$.data.parsedKeywordsJson.version").value("v1.0.0"))
                .andExpect(jsonPath("$.data.parsedKeywordsJson.data.keywords").isArray())
                .andExpect(jsonPath("$.data.parsedVersion").value("v1.0.0"))
                .andExpect(jsonPath("$.data.parsedAt").isNotEmpty())
                // jd_text 原文不变
                .andExpect(jsonPath("$.data.jdText").value(
                        "负责 Spring Boot 微服务开发,熟悉 MySQL/Redis,3 年以上经验,本科及以上学历。"));
    }

    // ---- 4. 未登录 ----

    @Test
    @Order(5)
    @DisplayName("未登录访问 POST 返回 40101(安全框架统一拦截)")
    void postWithoutAuth_40101() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"未登录","jdText":"测试文本"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }
}
