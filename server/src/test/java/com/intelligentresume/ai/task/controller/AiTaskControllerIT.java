package com.intelligentresume.ai.task.controller;

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
 * AI 任务控制器集成测试（MockMvc + H2 + Flyway）。
 * 覆盖:同意授权、任务创建 202、未授权 40302、跨用户 40401、幂等性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiTaskControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String tokenA;
    private static String tokenB;
    private static Long taskId;

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

    private void grantConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.1.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["JOB_GENERATION", "RESUME_OPTIMIZE"],
                                  "dataCategories": ["resume", "career_material"],
                                  "noticeHash": "test-hash-abc123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("GRANTED"));
    }

    // ---- 准备 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 A 和 B")
    void registerUsers() throws Exception {
        tokenA = registerAndGetToken("ai_user_a", "ai_user_a@example.com", "correcthorse");
        tokenB = registerAndGetToken("ai_user_b", "ai_user_b@example.com", "correcthorse");
        assertNotNull(tokenA);
        assertNotNull(tokenB);
    }

    @Test
    @Order(2)
    @DisplayName("准备: 用户 A 授权 AI 同意")
    void grantConsentForUserA() throws Exception {
        grantConsent(tokenA);
    }

    // ---- 1. 已授权创建任务 → 202 ----

    @Test
    @Order(3)
    @DisplayName("POST /api/ai/generate-resume-for-job 已授权 → 202 PENDING")
    void createTask_consented_202() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "test-idem-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "RESUME_OPTIMIZE",
                                  "input": {"prompt": "生成简历"}
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.taskType").value("RESUME_OPTIMIZE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        taskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    // ---- 2. 未授权创建任务 → 40302 ----

    @Test
    @Order(4)
    @DisplayName("未授权 AI 同意创建任务 → 403 + 40302")
    void createTask_notConsented_40302() throws Exception {
        mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType": "RESUME_OPTIMIZE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ---- 3. 跨用户查询 → 40401 ----

    @Test
    @Order(5)
    @DisplayName("跨用户查询任务 → 404 + 40401")
    void getTask_crossUser_40401() throws Exception {
        mockMvc.perform(get("/api/ai/tasks/" + taskId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 4. 幂等: 相同 key + 相同内容 → 返回同一任务 ----

    @Test
    @Order(6)
    @DisplayName("幂等: 相同 Idempotency-Key + 相同内容 → 返回同一任务 ID")
    void createTask_idempotent_sameFingerprint_returnsSameTask() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "test-idem-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "RESUME_OPTIMIZE",
                                  "input": {"prompt": "生成简历"}
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Long returnedId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
        assertEquals(taskId, returnedId, "幂等请求应返回相同任务 ID");
    }

    // ---- 5. 同意状态查询 ----

    @Test
    @Order(7)
    @DisplayName("GET /api/ai/consent 返回当前同意状态")
    void getConsent_returnsCurrentStatus() throws Exception {
        mockMvc.perform(get("/api/ai/consent")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.policyVersion").value("v1.1.0"));
    }

    // ---- 6. 未登录 → 403 ----

    @Test
    @Order(8)
    @DisplayName("未登录访问 AI 接口 → 403（Spring Security 拦截）")
    void withoutAuth_403() throws Exception {
        mockMvc.perform(post("/api/ai/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType": "RESUME_OPTIMIZE"}
                                """))
                .andExpect(status().isForbidden());
    }
}
