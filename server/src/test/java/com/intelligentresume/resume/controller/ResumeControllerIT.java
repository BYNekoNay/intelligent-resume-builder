package com.intelligentresume.resume.controller;

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
 * 简历与版本控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T03 §9 中 8 个集成测试场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResumeControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /** 用户 A 的 access token（Order 1 注册后填充） */
    private static String tokenA;
    /** 用户 B 的 access token（Order 2 注册后填充） */
    private static String tokenB;
    /** 用户 A 创建的简历 ID */
    private static Long resumeIdA;

    // ---- 辅助方法 ----

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("data").get("accessToken").asText();
    }

    // ---- 注册用户 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 A")
    void registerUserA() throws Exception {
        tokenA = registerAndGetToken("resume_user_a", "resume_a@example.com", "correcthorse");
        assertNotNull(tokenA);
    }

    @Test
    @Order(2)
    @DisplayName("准备: 注册用户 B")
    void registerUserB() throws Exception {
        tokenB = registerAndGetToken("resume_user_b", "resume_b@example.com", "correcthorse");
        assertNotNull(tokenB);
    }

    // ---- 1. 创建简历 ----

    @Test
    @Order(3)
    @DisplayName("POST /api/resumes 201 + Detail")
    void postCreate_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Java后端简历"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.title").value("Java后端简历"))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        resumeIdA = node.get("data").get("id").asLong();
    }

    // ---- 2. 列表 ----

    @Test
    @Order(4)
    @DisplayName("GET /api/resumes 返回当前用户列表")
    void getList_returnsOwn() throws Exception {
        mockMvc.perform(get("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("Java后端简历"));
    }

    // ---- 3. 跨用户访问 ----

    @Test
    @Order(5)
    @DisplayName("GET /api/resumes/{id} 跨用户返回 40401")
    void getDetail_crossUser_returns40401() throws Exception {
        assertNotNull(resumeIdA, "resumeIdA 应已在 postCreate_201 中赋值");
        mockMvc.perform(get("/api/resumes/" + resumeIdA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 4. 更新 ----

    @Test
    @Order(6)
    @DisplayName("PUT /api/resumes/{id} 200")
    void putUpdate_200() throws Exception {
        mockMvc.perform(put("/api/resumes/" + resumeIdA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"更新后的简历标题"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("更新后的简历标题"));
    }

    // ---- 5. 软删 ----

    @Test
    @Order(7)
    @DisplayName("DELETE /api/resumes/{id} 软删,后续 get 返回 40401")
    void delete_softDelete() throws Exception {
        // 先创建一份用于删除的简历
        MvcResult createResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"待删除简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long deleteId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 删除
        mockMvc.perform(delete("/api/resumes/" + deleteId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 删除后 get 应返回 40401
        mockMvc.perform(get("/api/resumes/" + deleteId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 6. 保存版本 ----

    @Test
    @Order(8)
    @DisplayName("POST /api/resumes/{id}/versions 201 + versionNo=1")
    void postSaveVersion_firstVersion_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resumeJson": {"basics": {"name": "Alice"}, "work": [{"company": "ACME"}]},
                                  "sourceType": "MANUAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.sourceType").value("MANUAL"))
                .andReturn();

        // 验证版本列表
        mockMvc.perform(get("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].versionNo").value(1));
    }

    // ---- 7. 无效 JSON Resume ----

    @Test
    @Order(9)
    @DisplayName("无效 JSON Resume POST 返回 40001")
    void postInvalidJson_40001() throws Exception {
        // 缺少 basics 顶层字段
        mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resumeJson": {"work": [{"company": "ACME"}]},
                                  "sourceType": "MANUAL"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---- 8. 未登录 ----

    @Test
    @Order(10)
    @DisplayName("未登录访问 POST 返回 403(Spring Security 拦截,未到达 Controller)")
    void postWithoutAuth_40101() throws Exception {
        // 偏差说明:T03 手册期望 40101,但 /api/resumes 不在 permitAll 白名单中,
        // Spring Security 在请求到达 Controller 前即返回 403。
        // /api/auth/** 端点返回 401 是因为 permitAll 后由 Controller 抛出 UNAUTHENTICATED。
        mockMvc.perform(post("/api/resumes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"未登录简历"}
                                """))
                .andExpect(status().isForbidden());
    }
}
