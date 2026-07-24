package com.intelligentresume.ai.generation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
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
 * 岗位定制生成控制器集成测试（MockMvc + H2 + Flyway）。
 * 覆盖:创建 202、跨用户任务 40401、跨用户资料 40401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobGenerationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;

    private static String tokenA;
    private static String tokenB;
    private static Long materialIdA;
    private static Long jdIdA;
    private static Long resumeIdA;
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
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    // ---- 准备 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 A/B + 同意 + 资料 + JD + 简历")
    void setup() throws Exception {
        // 清理残留任务(避免 batch-size=1 领取旧任务)
        taskRepository.deleteAll();

        tokenA = registerAndGetToken("gen_user_a", "gen_user_a@example.com", "correcthorse");
        tokenB = registerAndGetToken("gen_user_b", "gen_user_b@example.com", "correcthorse");

        // 用户 A 授权 AI 同意
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.0.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["JOB_GENERATION"],
                                  "dataCategories": ["resume"],
                                  "noticeHash": "hash"
                                }
                                """))
                .andExpect(status().isCreated());

        // 用户 B 授权 AI 同意
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.0.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["JOB_GENERATION"],
                                  "dataCategories": ["resume"],
                                  "noticeHash": "hash"
                                }
                                """))
                .andExpect(status().isCreated());

        // 用户 A 创建职业资料
        MvcResult matResult = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "WORK_EXPERIENCE",
                                  "title": "Java开发经验",
                                  "contentJson": {"company": "测试公司", "role": "Java开发"},
                                  "sourceText": "负责 Spring Boot 微服务开发"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        materialIdA = objectMapper.readTree(matResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 用户 A 创建 JD
        MvcResult jdResult = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java后端工程师",
                                  "companyName": "某科技公司",
                                  "jdText": "负责 Spring Boot 微服务开发,熟悉 MySQL/Redis,3 年以上经验,本科及以上学历。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        jdIdA = objectMapper.readTree(jdResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 用户 A 创建简历
        MvcResult resumeResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "我的简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        resumeIdA = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    // ---- 1. 创建任务 202 ----

    @Test
    @Order(2)
    @DisplayName("POST 已同意 + 资料 + JD 返回 202 + taskId")
    void postCreate_202() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "gen-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "JOB_GENERATION",
                                  "targetResumeId": %d,
                                  "jobDescriptionId": %d,
                                  "input": {
                                    "includedMaterialIds": [%d],
                                    "preferredMaterialIds": [],
                                    "excludedMaterialIds": []
                                  }
                                }
                                """.formatted(resumeIdA, jdIdA, materialIdA)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        taskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    // ---- 2. 跨用户查询任务 40401 ----

    @Test
    @Order(3)
    @DisplayName("GET 任务跨用户返回 40401")
    void getTask_crossUser_40401() throws Exception {
        mockMvc.perform(get("/api/ai/tasks/" + taskId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 3. 跨用户资料 40401 ----

    @Test
    @Order(4)
    @DisplayName("POST 跨用户 materialId 返回 40401")
    void postCreate_crossUserMaterial_40401() throws Exception {
        // 用户 B 尝试使用用户 A 的资料 ID
        mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "JOB_GENERATION",
                                  "targetResumeId": 1,
                                  "jobDescriptionId": 1,
                                  "input": {
                                    "includedMaterialIds": [%d]
                                  }
                                }
                                """.formatted(materialIdA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }
}
