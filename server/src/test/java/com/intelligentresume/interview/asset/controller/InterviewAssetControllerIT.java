package com.intelligentresume.interview.asset.controller;

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
class InterviewAssetControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    private static String tokenA;
    private static String tokenB;
    private static long jobId;
    private static long recordId;
    private static long assetId;

    @Test @Order(1)
    void prepareInterviewRecord() throws Exception {
        tokenA = register("asset_a", "asset_a@example.com");
        tokenB = register("asset_b", "asset_b@example.com");
        jobId = id(postJson("/api/jobs", tokenA, "{\"title\":\"Platform Engineer\",\"jdText\":\"Java Docker Kubernetes platform engineering\"}"));
        MvcResult started = mockMvc.perform(post("/api/interviews/start").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXTERNAL_RESUME\",\"externalResumeText\":\"Java platform engineer\",\"jobDescriptionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(jobId)))
                .andExpect(status().isOk()).andReturn();
        long interviewId = objectMapper.readTree(started.getResponse().getContentAsString()).path("data").path("interviewId").asLong();
        MvcResult answered = mockMvc.perform(post("/api/interviews/" + interviewId + "/answer")
                        .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"I designed a Kubernetes platform and reduced deployment time by 40 percent.\"}"))
                .andExpect(status().isOk()).andReturn();
        recordId = objectMapper.readTree(answered.getResponse().getContentAsString()).path("data").path("recordId").asLong();
    }

    @Test @Order(2)
    void createsListsSearchesAndFilters() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/interview-answer-assets").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interviewRecordId\":%d,\"questionText\":\"Describe Kubernetes delivery\",\"originalAnswerText\":\"I shipped a platform\",\"suggestedAnswerText\":\"Add measurable results\",\"feedbackJson\":{\"score\":80}}".formatted(recordId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.interviewRecordId").value(recordId)).andReturn();
        assetId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(get("/api/interview-answer-assets").param("keyword", "kubernetes").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/interview-answer-assets").param("jobDescriptionId", String.valueOf(jobId)).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(assetId));
    }

    @Test @Order(3)
    void updatesDeletesAndRejectsCrossUserAccess() throws Exception {
        mockMvc.perform(put("/api/interview-answer-assets/" + assetId).header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionText\":\"Updated question\",\"originalAnswerText\":\"Updated answer\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionText").value("Updated question"))
                .andExpect(jsonPath("$.data.interviewRecordId").value(recordId));
        mockMvc.perform(put("/api/interview-answer-assets/" + assetId).header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionText\":\"No\",\"originalAnswerText\":\"No\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/interview-answer-assets").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interviewRecordId\":%d,\"questionText\":\"No\",\"originalAnswerText\":\"No\"}".formatted(recordId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/interview-answer-assets/" + assetId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
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
