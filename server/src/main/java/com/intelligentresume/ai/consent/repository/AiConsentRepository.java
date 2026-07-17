package com.intelligentresume.ai.consent.repository;

import com.intelligentresume.ai.consent.domain.AiConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiConsentRepository extends JpaRepository<AiConsent, Long> {

    Optional<AiConsent> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}