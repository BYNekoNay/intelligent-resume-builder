package com.intelligentresume.ai.optimize.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InlineOptimizeControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;
    private static String token;
    private static boolean consentGranted;
    private static long resumeVersionId;
    private static long jobDescriptionId;

    @Test
    void validInlineOptimizeReturns202() throws Exception {
        ensureConsent();
        ensureOwnedResources();
        String payload = "{\"resumeVersionId\": %d, \"section\": \"work\", \"content\": \"Led platform engineering team\", \"jobDescriptionId\": %d}"
                .formatted(resumeVersionId, jobDescriptionId);
        MvcResult result = mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("INLINE_OPTIMIZE"))
                .andReturn();

        long taskId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        Object jobDescriptionId = ((java.util.Map<?, ?>) taskRepository.findById(taskId).orElseThrow()
                .getInputSnapshotJson().get("input")).get("jobDescriptionId");
        assertEquals(InlineOptimizeControllerIT.jobDescriptionId, ((Number) jobDescriptionId).longValue());
    }

    @Test
    void validatesMissingFields() throws Exception {
        ensureToken();
        String payload = "{\"resumeVersionId\": null, \"section\": \"\", \"content\": \"\"}";
        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        String payload = "{\"resumeVersionId\": 1, \"section\": \"work\", \"content\": \"test\"}";
        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    void validAchievementGuidanceReturns202() throws Exception {
        ensureConsent();
        ensureOwnedResources();
        String payload = "{\"resumeVersionId\": %d, \"section\": \"work\", \"content\": \"Led platform team\"}"
                .formatted(resumeVersionId);
        mockMvc.perform(post("/api/ai/achievement-guidance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("ACHIEVEMENT_GUIDANCE"));
    }

    @Test
    void rejectsMalformedPayload() throws Exception {
        ensureToken();
        String payload = "not json at all";
        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedPayload() throws Exception {
        ensureToken();
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "resumeVersionId", 1,
                "section", "work",
                "content", "x".repeat(30001)));
        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void rejectsWithdrawnConsent() throws Exception {
        String withdrawnToken = register("opt_withdrawn", "opt-withdrawn@example.com");
        long withdrawnVersionId = createOwnedVersion(withdrawnToken, "Withdrawn resume");
        grantConsent(withdrawnToken);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/ai/consent")
                        .header("Authorization", "Bearer " + withdrawnToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"section\":\"work\",\"content\":\"test\"}".formatted(withdrawnVersionId))
                        .header("Authorization", "Bearer " + withdrawnToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void rejectsMissingAndForeignResourcesAsNotFound() throws Exception {
        ensureConsent();
        ensureOwnedResources();
        String foreignToken = register("opt_foreign", "opt-foreign@example.com");

        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"section\":\"work\",\"content\":\"test\"}".formatted(resumeVersionId))
                        .header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/inline-optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"section\":\"work\",\"content\":\"test\",\"jobDescriptionId\":999999}".formatted(resumeVersionId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private void ensureToken() throws Exception {
        if (token != null) return;
        token = register("opt_user", "opt@example.com");
    }

    private void ensureConsent() throws Exception {
        ensureToken();
        if (consentGranted) return;
        grantConsent(token);
        consentGranted = true;
    }

    private void ensureOwnedResources() throws Exception {
        if (resumeVersionId != 0) return;
        resumeVersionId = createOwnedVersion(token, "Optimize resume");
        jobDescriptionId = id(mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Backend Engineer\",\"companyName\":\"ACME\",\"jdText\":\"Java Spring platform engineering\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private long createOwnedVersion(String accessToken, String title) throws Exception {
        long resumeId = id(mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated()).andReturn());
        return id(mockMvc.perform(post("/api/resumes/" + resumeId + "/versions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeJson\":{\"basics\":{\"name\":\"Alice\"}},\"sourceType\":\"MANUAL\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}"
                                .formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private void grantConsent(String accessToken) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.2.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["INLINE_OPTIMIZE", "ACHIEVEMENT_GUIDANCE"],
                                  "dataCategories": ["resume"],
                                  "noticeHash": "inline-optimize-test"
                                }
                                """))
                .andExpect(status().isCreated());
    }
}
