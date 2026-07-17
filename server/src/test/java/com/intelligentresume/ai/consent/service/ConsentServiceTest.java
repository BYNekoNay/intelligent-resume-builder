package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ConsentServiceTest {

    @Autowired private ConsentService consentService;
    @Autowired private AiConsentRepository consentRepository;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;

    @Test
    void currentReturnsLatestEventWithoutDeletingConsentHistory() {
        String username = "consent_" + UUID.randomUUID().toString().substring(0, 8);
        authService.register(new RegisterRequest(username, username + "@example.com", "StrongPassword!1"));
        long userId = userRepository.findByUsername(username).orElseThrow().getId();
        ConsentResponse granted = consentService.grant(new ConsentRequest(
                "mvp-v1", "mock", List.of("JOB_GENERATION"), List.of("RESUME"), "notice"), userId);

        assertThat(consentService.current(userId)).isNotNull();
        assertThat(consentService.current(userId).eventType()).isEqualTo(AiConsent.ConsentEventType.GRANTED);
        assertThat(consentService.current(userId).providerCode()).isEqualTo("mock");

        consentService.withdraw(userId);

        assertThat(consentService.current(userId).eventType()).isEqualTo(AiConsent.ConsentEventType.WITHDRAWN);
        assertThat(consentRepository.findById(granted.id())).isPresent();
    }
}
