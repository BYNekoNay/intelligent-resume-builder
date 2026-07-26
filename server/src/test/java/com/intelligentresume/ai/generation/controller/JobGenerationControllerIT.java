package com.intelligentresume.ai.generation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobGenerationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void selectionTaskRequiresCurrentConsentAndOwnedJobAndMaterials() throws Exception {
        String token = register("selection_controller_user", "selection_controller@example.com");
        grantSelectionConsent(token);
        long jobId = createJob(token);
        long materialId = createMaterial(token);

        MvcResult result = mockMvc.perform(post("/api/ai/select-materials-for-job")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "selection-controller-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobDescriptionId": %d,
                                 "includedMaterialIds": [%d],
                                 "preferredMaterialIds": [],
                                 "excludedMaterialIds": []}
                                """.formatted(jobId, materialId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("JOB_MATERIAL_SELECTION"))
                .andExpect(jsonPath("$.data.jobDescriptionId").value(jobId))
                .andReturn();

        assertNotNull(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong());
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}"
                                .formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private void grantSelectionConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyVersion":"v1.1.0","providerCode":"bailian",
                                 "taskScopes":["JOB_MATERIAL_SELECTION","JOB_GENERATION"],
                                 "dataCategories":["JOB_DESCRIPTION","CAREER_MATERIAL","PERSONAL_PROFILE"],
                                 "noticeHash":"test"}
                                """))
                .andExpect(status().isCreated());
    }

    private long createJob(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java Backend Engineer\",\"companyName\":\"Example\",\"jdText\":\"Build Java Spring Boot backend services with MySQL and Redis.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long createMaterial(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialType\":\"WORK_EXPERIENCE\",\"title\":\"Java backend experience\",\"sourceText\":\"Built Spring Boot services using MySQL and Redis.\",\"contentJson\":{\"company\":\"Example\",\"position\":\"Java Engineer\"},\"usagePreference\":\"NORMAL\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
