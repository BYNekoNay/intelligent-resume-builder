package com.intelligentresume.resume.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeResponse;
import com.intelligentresume.resume.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private ResumeService resumeService;
    @Autowired private UserRepository userRepository;

    @Test
    void updatesTitleWithPut() throws Exception {
        TestUser user = createUser();
        ResumeResponse resume = createResume(user.userId());

        mockMvc.perform(put("/api/resumes/{id}", resume.id())
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Updated resume"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(resume.id()))
                .andExpect(jsonPath("$.data.title").value("Updated resume"));
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        TestUser user = createUser();
        ResumeResponse resume = createResume(user.userId());

        mockMvc.perform(put("/api/resumes/{id}", resume.id())
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void hidesOtherUsersResumeWhenUpdatingTitle() throws Exception {
        TestUser owner = createUser();
        TestUser otherUser = createUser();
        ResumeResponse resume = createResume(owner.userId());

        mockMvc.perform(put("/api/resumes/{id}", resume.id())
                        .header("Authorization", "Bearer " + otherUser.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Unauthorized update\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    private TestUser createUser() {
        String username = "resume_" + UUID.randomUUID().toString().substring(0, 8);
        TokenResponse tokens = authService.register(new RegisterRequest(
                username, username + "@example.com", "StrongPassword!1"));
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();
        return new TestUser(userId, tokens.accessToken());
    }

    private ResumeResponse createResume(Long userId) {
        return resumeService.create(new ResumeCreateRequest(
                "Original resume", Map.of("basics", Map.of("name", "Test User"))), userId);
    }

    private record TestUser(Long userId, String accessToken) { }
}
