package com.intelligentresume.personalprofile.controller;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PersonalProfileControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void profileCanBeReadCreatedAndUpdated() throws Exception {
        String token = register("profile");

        mockMvc.perform(get("/api/personal-profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").doesNotExist());

        mockMvc.perform(put("/api/personal-profile")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Zhang San","email":"zhang@example.com","phone":"13800000000",
                                 "location":"Shanghai","website":"https://example.com","profileSummary":"Backend engineer",
                                 "targetRoleTitles":["Java Engineer"],"targetSeniority":"Senior",
                                 "targetIndustries":["Internet"],"targetWorkPreferences":["Remote"],
                                 "careerPositioningSummary":"Platform specialist"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Zhang San"))
                .andExpect(jsonPath("$.data.website").value("https://example.com"))
                .andExpect(jsonPath("$.data.targetRoleTitles[0]").value("Java Engineer"))
                .andExpect(jsonPath("$.data.targetSeniority").value("Senior"))
                .andExpect(jsonPath("$.data.careerPositioningSummary").value("Platform specialist"));

        mockMvc.perform(put("/api/personal-profile")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Li Si\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Li Si"));

        mockMvc.perform(get("/api/personal-profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Li Si"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void importSuggestionUsesOwnedCurrentVersionAndDoesNotPersistIt() throws Exception {
        String token = register("import");
        MvcResult created = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Source resume","resumeJson":{"basics":{"name":"Alice","email":"alice@example.com",
                                "phone":"13900000000","location":"Beijing","website":"https://alice.example.com","summary":"Java engineer"}}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long resumeId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/personal-profile/import-suggestion")
                        .param("resumeId", Long.toString(resumeId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Alice"))
                .andExpect(jsonPath("$.data.website").value("https://alice.example.com"))
                .andExpect(jsonPath("$.data.profileSummary").value("Java engineer"));

        mockMvc.perform(get("/api/personal-profile").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").doesNotExist());
    }

    @Test
    void anotherUsersResumeCannotBeImported() throws Exception {
        String ownerToken = register("owner");
        String otherToken = register("other");
        MvcResult created = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Private\",\"resumeJson\":{\"basics\":{\"name\":\"Private User\"}}}"))
                .andExpect(status().isCreated())
                .andReturn();
        long resumeId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(get("/api/personal-profile/import-suggestion")
                        .param("resumeId", Long.toString(resumeId))
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void profileEndpointsRequireAuthenticationAndValidateEmail() throws Exception {
        mockMvc.perform(get("/api/personal-profile"))
                .andExpect(status().isForbidden());

        String token = register("invalid");
        mockMvc.perform(put("/api/personal-profile")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    private String register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s_%s","email":"%s_%s@example.com","password":"correcthorse"}
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
