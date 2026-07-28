package com.intelligentresume.ats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtsControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    private static String tokenA;
    private static String tokenB;
    private static long versionId;
    private static long jobId;

    @Test
    @Order(1)
    void prepare() throws Exception {
        tokenA = register("ats_a", "ats_a@example.com");
        tokenB = register("ats_b", "ats_b@example.com");
        long resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"ATS resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice","summary":"Java Spring Boot engineer"},"work":[{"company":"ACME","description":"MySQL reliability"}],"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        jobId = id(postJson("/api/jobs", tokenA, """
                {"title":"Backend Engineer","jdText":"Java Spring Boot MySQL Redis"}
                """));
    }

    @Test
    @Order(2)
    void returnsExplainablePersistedContract() throws Exception {
        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.checks.structure").isNumber())
                .andExpect(jsonPath("$.data.checks.keywordCoverage").isNumber())
                .andExpect(jsonPath("$.data.passedChecks").isArray())
                .andExpect(jsonPath("$.data.risks").isArray())
                .andExpect(jsonPath("$.data.priorities").isArray())
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty());
    }

    @Test
    @Order(3)
    void rejectsCrossUserResources() throws Exception {
        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId)))
                .andExpect(status().isNotFound());
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}".formatted(username, email)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    private MvcResult postJson(String path, String token, String json) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
    }
    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
