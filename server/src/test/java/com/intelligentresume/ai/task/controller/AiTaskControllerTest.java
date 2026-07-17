package com.intelligentresume.ai.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiTaskControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    @Test
    void returnsConsentRequiredCodeWhenCreatingTaskWithoutConsent() throws Exception {
        String accessToken = createAccessToken();

        createTask(accessToken, "missing-consent")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void returnsConsentRequiredCodeAfterConsentIsWithdrawn() throws Exception {
        String accessToken = createAccessToken();

        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyVersion":"v1","providerCode":"mock","taskScopes":["JOB_GENERATION"],
                                 "dataCategories":["resume"],"noticeHash":"test-notice"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/ai/consent")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        createTask(accessToken, "withdrawn-consent")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    private String createAccessToken() {
        String username = "ai_task_" + UUID.randomUUID().toString().substring(0, 8);
        return authService.register(new RegisterRequest(
                username, username + "@example.com", "StrongPassword!1")).accessToken();
    }

    private org.springframework.test.web.servlet.ResultActions createTask(String accessToken, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetResumeId", 1,
                                "jobDescriptionId", 1,
                                "includedMaterialIds", List.of()))));
    }
}
