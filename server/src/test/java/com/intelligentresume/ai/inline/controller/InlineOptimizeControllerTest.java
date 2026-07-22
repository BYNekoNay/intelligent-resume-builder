package com.intelligentresume.ai.inline.controller;

import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.service.ResumeService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InlineOptimizeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ConsentService consentService;
    @Autowired private ResumeService resumeService;

    @Test
    void returnsTraceableCandidatesWithoutCreatingVersion() throws Exception {
        UserFixture user = createUser(true);
        ResumeFixture resume = createResumeVersion(user.userId());

        mockMvc.perform(post("/api/ai/inline-optimize")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId":%d,"section":"projectDescription",
                                 "content":"负责接口开发和数据库设计"}
                                """.formatted(resume.versionId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").isNumber())
                .andExpect(jsonPath("$.data.originalContent").value("负责接口开发和数据库设计"))
                .andExpect(jsonPath("$.data.candidates.length()").value(3))
                .andExpect(jsonPath("$.data.candidates[0].content").isNotEmpty())
                .andExpect(jsonPath("$.data.candidates[0].suggestion").isNotEmpty())
                .andExpect(jsonPath("$.data.requiresManualConfirmation").value(true));

        // Creating the resume creates version 1; the fixture adds the baseline version 2.
        // Inline suggestions must not create a third version before explicit confirmation.
        org.assertj.core.api.Assertions.assertThat(resumeService.listVersions(resume.resumeId(), user.userId())).hasSize(2);
    }

    @Test
    void requiresAiConsent() throws Exception {
        UserFixture user = createUser(false);
        ResumeFixture resume = createResumeVersion(user.userId());

        mockMvc.perform(post("/api/ai/inline-optimize")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId":%d,"section":"summary","content":"熟悉 Java"}
                                """.formatted(resume.versionId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    void hidesCrossUserResumeVersion() throws Exception {
        UserFixture owner = createUser(true);
        ResumeFixture resume = createResumeVersion(owner.userId());
        UserFixture attacker = createUser(true);

        mockMvc.perform(post("/api/ai/inline-optimize")
                        .header("Authorization", "Bearer " + attacker.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId":%d,"section":"summary","content":"熟悉 Java"}
                                """.formatted(resume.versionId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    private UserFixture createUser(boolean consented) {
        String username = "inline_" + UUID.randomUUID().toString().substring(0, 8);
        TokenResponse token = authService.register(new RegisterRequest(
                username, username + "@example.com", "StrongPassword!1"));
        long userId = userRepository.findByUsername(username).orElseThrow().getId();
        if (consented) {
            consentService.grant(new ConsentRequest(
                    "v1", "mock", List.of("INLINE_OPTIMIZE"), List.of("resume_text"), "test-notice"), userId);
        }
        return new UserFixture(userId, token.accessToken());
    }

    private ResumeFixture createResumeVersion(long userId) {
        long resumeId = resumeService.create(new ResumeCreateRequest(
                "测试简历", Map.of("basics", Map.of("name", "测试用户"))), userId).id();
        long versionId = resumeService.createVersion(resumeId, new ResumeVersionCreateRequest(
                Map.of("basics", Map.of("name", "测试用户")), ResumeVersion.SourceType.MANUAL, "基线"), userId).id();
        return new ResumeFixture(resumeId, versionId);
    }

    private record UserFixture(long userId, String accessToken) {}
    private record ResumeFixture(long resumeId, long versionId) {}
}
