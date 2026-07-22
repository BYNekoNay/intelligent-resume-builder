package com.intelligentresume.ai.guidance.service;

import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.guidance.dto.AchievementGuidanceRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AchievementGuidanceServiceTest {

    @Autowired private AchievementGuidanceService service;
    @Autowired private ConsentService consentService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeService resumeService;

    @Test
    void returnsQuestionsWithoutCreatingANewResumeVersion() {
        long userId = createUser();
        grantConsent(userId);
        var resume = resumeService.create(new ResumeCreateRequest("Guidance resume",
                Map.of("basics", Map.of("name", "User"))), userId);
        long versionId = resume.currentVersionId();

        var response = service.guide(new AchievementGuidanceRequest(versionId, "work",
                "Maintained a Spring Boot service"), userId);

        assertThat(response.questions()).isNotEmpty();
        assertThat(response.writesBackAutomatically()).isFalse();
        assertThat(resumeService.listVersions(resume.id(), userId)).hasSize(1);
    }

    @Test
    void requiresConsentAndHidesForeignResumeVersions() {
        long ownerId = createUser();
        var ownerResume = resumeService.create(new ResumeCreateRequest("Owner resume",
                Map.of("basics", Map.of("name", "Owner"))), ownerId);
        long requesterId = createUser();

        assertThatThrownBy(() -> service.guide(new AchievementGuidanceRequest(ownerResume.currentVersionId(), "work", "Built APIs"), requesterId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONSENT_REQUIRED);

        grantConsent(requesterId);
        assertThatThrownBy(() -> service.guide(new AchievementGuidanceRequest(ownerResume.currentVersionId(), "work", "Built APIs"), requesterId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private long createUser() {
        String username = "guide_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void grantConsent(long userId) {
        consentService.grant(new ConsentRequest("v1", "mock", List.of("ACHIEVEMENT_GUIDANCE"),
                List.of("resume_text"), "test-notice"), userId);
    }
}
