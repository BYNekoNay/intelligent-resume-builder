package com.intelligentresume.scoring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.scoring.repository.MatchResultRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 评分控制器集成测试（MockMvc + H2 + Flyway）。
 * 覆盖：POST 200、GET 200、跨用户 40401、未登录 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScoringControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MatchResultRepository matchResultRepository;

    private static String tokenA;
    private static String tokenB;
    private static Long versionId;
    private static Long jdId;
    private static Long matchResultId;

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

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 + 简历版本 + JD")
    void setup() throws Exception {
        matchResultRepository.deleteAll();

        tokenA = registerAndGetToken("score_user_a", "score_user_a@example.com", "correcthorse");
        tokenB = registerAndGetToken("score_user_b", "score_user_b@example.com", "correcthorse");

        // 创建简历
        MvcResult resumeResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "评分测试简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long resumeId = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 保存简历版本（含技能和工作经历）
        MvcResult versionResult = mockMvc.perform(post("/api/resumes/" + resumeId + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "MANUAL",
                                  "resumeJson": {
                                    "basics": {"label": "Java开发工程师", "summary": "5年Java后端开发经验"},
                                    "skills": [
                                      {"name": "Java", "keywords": ["Spring Boot", "MySQL", "Redis"]},
                                      {"name": "DevOps", "keywords": ["Docker"]}
                                    ],
                                    "work": [
                                      {"company": "ABC科技", "position": "Java开发", "highlights": ["负责Spring Boot微服务开发", "优化MySQL查询性能"]}
                                    ]
                                  },
                                  "optimizationSummary": "初始版本"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        versionId = objectMapper.readTree(versionResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 创建 JD
        MvcResult jdResult = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java后端工程师",
                                  "companyName": "某科技公司",
                                  "jdText": "负责 Spring Boot 微服务开发,熟悉 MySQL、Redis,了解 Docker 和 Kubernetes,3 年以上经验,本科及以上学历。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        jdId = objectMapper.readTree(jdResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/scoring/match 200 + 返回 matchResultId")
    void postMatch_200() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/scoring/match")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId": %d, "jobDescriptionId": %d}
                                """.formatted(versionId, jdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matchResultId").isNumber())
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.ruleVersion").value("v1.0.0"))
                .andExpect(jsonPath("$.data.explanation.disclaimer").value("本结果为 JD 规则覆盖度，非企业 ATS 结果、非录用概率"))
                .andReturn();

        matchResultId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("matchResultId").asLong();
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/scoring/results/{id} 200")
    void getResult_200() throws Exception {
        mockMvc.perform(get("/api/scoring/results/" + matchResultId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.ruleVersion").value("v1.0.0"));
    }

    @Test
    @Order(4)
    @DisplayName("GET 跨用户返回 40401")
    void getResult_crossUser_40401() throws Exception {
        mockMvc.perform(get("/api/scoring/results/" + matchResultId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @Order(5)
    @DisplayName("未登录访问 POST 返回 40101")
    void postMatch_unauthenticated_40101() throws Exception {
        mockMvc.perform(post("/api/scoring/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId": 1, "jobDescriptionId": 1}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }
}
