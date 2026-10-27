package com.intelligentresume.ats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.ai.bailian.api-key=test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtsConsentFallbackIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void returnsRulesWhenProviderIsConfiguredButConsentIsMissing() throws Exception {
        String token = register();
        long resumeId = id(postJson("/api/resumes", token, "{\"title\":\"Consent ATS resume\"}"));
        long versionId = id(postJson("/api/resumes/" + resumeId + "/versions", token, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[{"description":"Built Java services"}],"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        long jobId = id(postJson("/api/jobs", token, """
                {"title":"Backend Engineer","jdText":"Java Spring Boot backend engineering role"}
                """));

        // R32 / D-AI-11：不带预览令牌的 useAi=true 检查不再直接启动 AI ——
        // 规则结论照常落库，AI 分支标记为 PREVIEW_REQUIRED，由客户端预览后走 ai-retry。
        long checkId = id(mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ats-consent-ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d}".formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.data.fallback.code").value("PREVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.fallback.retryable").value(true))
                .andExpect(jsonPath("$.data.aiTaskId").doesNotExist())
                .andReturn());

        // 预览并确认后带令牌重试 —— 这一次才真正走到授权校验，落成 CONSENT_REQUIRED
        mockMvc.perform(post("/api/ats/checks/" + checkId + "/ai-retry")
                        .header("Authorization", "Bearer " + token)
                        .header(AiPreviewGuard.HEADER, previewToken(checkId, token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_FALLBACK"))
                .andExpect(jsonPath("$.data.fallback.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.data.fallback.consentRequired").value(true))
                .andExpect(jsonPath("$.data.aiTaskId").doesNotExist());

        mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ats-consent-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"useAi\":false}"
                                .formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_ONLY"))
                .andExpect(jsonPath("$.data.analysisSource").value("RULES"))
                .andExpect(jsonPath("$.data.fallback").doesNotExist())
                .andExpect(jsonPath("$.data.aiTaskId").doesNotExist());
    }

    /** 走真实预览端点取令牌 —— 不允许手工拼令牌，否则测的就不是协议本身。 */
    private String previewToken(long checkId, String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ats/checks/" + checkId + "/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("previewToken").asText();
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ats_consent\",\"email\":\"ats_consent@example.com\",\"password\":\"correcthorse\"}"))
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
