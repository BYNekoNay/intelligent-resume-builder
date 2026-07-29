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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private static long atsResultId;

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
        MvcResult result = mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "ats-contract")
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
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.data.analysisSource").value("RULES"))
                .andExpect(jsonPath("$.data.fallback.code").value("AI_DISABLED"))
                .andReturn();

        atsResultId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(get("/api/ats/checks/" + atsResultId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(atsResultId))
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_FALLBACK"));
    }

    @Test
    @Order(3)
    void reusesMatchingIdempotentRequestAndRejectsChangedPayload() throws Exception {
        String body = "{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId);
        MvcResult repeated = mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "ats-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        long repeatedId = objectMapper.readTree(repeated.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        Assertions.assertEquals(atsResultId, repeatedId);

        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "ats-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"useAi\":false}".formatted(versionId, jobId)))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(4)
    void rejectsCrossUserResources() throws Exception {
        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", "ats-cross-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/ats/checks/" + atsResultId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/ats/checks/" + atsResultId + "/ai-retry")
                        .header("Authorization", "Bearer " + tokenB))
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
