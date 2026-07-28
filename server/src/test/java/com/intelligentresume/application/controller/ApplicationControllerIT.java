package com.intelligentresume.application.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String tokenA;
    private static String tokenB;
    private static long jobId;
    private static long resumeId;
    private static long versionId;
    private static long applicationId;

    @Test
    @Order(1)
    void prepareOwnedResources() throws Exception {
        tokenA = register("application_a", "application_a@example.com");
        tokenB = register("application_b", "application_b@example.com");
        jobId = id(postJson("/api/jobs", tokenA, """
                {"title":"Platform Engineer","companyName":"Example","jdText":"Java Spring reliability"}
                """));
        resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Platform resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[]},"sourceType":"MANUAL"}
                """));
    }

    @Test
    @Order(2)
    void createAndListReturnCompleteOwnedRecord() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobDescriptionId":%d,"resumeVersionId":%d,"status":"DRAFT","coverLetterText":"Hello"}
                                """.formatted(jobId, versionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobDescriptionId").value(jobId))
                .andExpect(jsonPath("$.data.resumeVersionId").value(versionId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.coverLetterText").value("Hello"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        applicationId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(applicationId));
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @Order(3)
    void updateAndStatusTransitionUseOptimisticVersion() throws Exception {
        mockMvc.perform(put("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobDescriptionId":%d,"resumeVersionId":%d,"status":"DRAFT","emailBodyText":"Interview request","version":0}
                                """.formatted(jobId, versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailBodyText").value("Interview request"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @Order(4)
    void crossUserCannotMutateAndOwnerCanDelete() throws Exception {
        mockMvc.perform(delete("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}".formatted(username, email)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private MvcResult postJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
