package com.intelligentresume.ats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.ai.bailian.api-key=test-key",
        "app.ai.quota.ATS_ANALYSIS=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtsQuotaFallbackIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void returnsRulesWhenAtsAiQuotaIsExhausted() throws Exception {
        String token = register();
        grantAtsConsent(token);
        long resumeId = id(postJson("/api/resumes", token, "{\"title\":\"Quota ATS resume\"}"));
        long versionId = id(postJson("/api/resumes/" + resumeId + "/versions", token, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[{"description":"Built Java services"}],"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        long jobId = id(postJson("/api/jobs", token, """
                {"title":"Backend Engineer","jdText":"Java Spring Boot backend engineering role"}
                """));

        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ats-quota")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.data.fallback.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.data.fallback.retryable").value(false));
    }

    private void grantAtsConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyVersion":"v1.2.0","providerCode":"bailian",
                                 "taskScopes":["ATS_ANALYSIS"],
                                 "dataCategories":["RESUME","JOB_DESCRIPTION"],
                                 "noticeHash":"ats-quota-test"}
                                """))
                .andExpect(status().isCreated());
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ats_quota\",\"email\":\"ats_quota@example.com\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private MvcResult postJson(String path, String token, String json) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
